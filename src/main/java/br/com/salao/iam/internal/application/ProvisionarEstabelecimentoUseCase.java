package br.com.salao.iam.internal.application;

import br.com.salao.iam.api.ErrosDoIam;
import br.com.salao.iam.internal.domain.DadosDoEstabelecimentoInvalidosException;
import br.com.salao.iam.internal.domain.NovoEstabelecimento;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.iam.internal.domain.Emails;
import br.com.salao.shared.manutencao.ConexaoDeManutencao;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * RT-IAM-001 — cria um estabelecimento novo.
 *
 * <h2>Por que este caso de uso é diferente de todos os outros</h2>
 *
 * <p>É a <strong>única operação legitimamente cross-tenant</strong> do sistema, e por isso a única
 * que não passa pela conexão da aplicação.
 *
 * <p>{@code salao_app} não teria como fazer isto nem com {@code insert} concedido: a policy
 * {@code tenant_isolado} tem {@code with check (id = current_setting('app.tenant_id'))}, então ela
 * só conseguiria inserir uma linha cujo id fosse o do tenant que ela já é. Para criar um tenant que
 * ainda não existe, isso é inútil — e tentar contornar afrouxando a policy desfaria o isolamento
 * inteiro para resolver um caso que acontece uma vez por cliente.
 *
 * <p>Vai pela role de plataforma ({@link ConexaoDeManutencao}, ADR-0010), a mesma categoria de
 * purga e retenção: operações que atravessam estabelecimentos por natureza.
 *
 * <h2>Sem transação, e sem evento</h2>
 *
 * <p>Sem {@code @Transactional} porque a conexão de plataforma não passa pelo gerenciador que
 * exige tenant — e ele exigiria um tenant que, por definição, ainda não existe. Um único
 * {@code INSERT} é atômico de qualquer forma.
 *
 * <p>Sem evento de domínio, deliberadamente: publicá-lo exigiria escrever no outbox pela conexão
 * da aplicação, em outra transação — sem atomicidade com o insert, que é exatamente a garantia
 * pela qual o outbox existe. Quando algo precisar reagir a provisionamento, isso se resolve
 * gravando a publicação pela mesma conexão, não fingindo que as duas escritas são uma só.
 */
public class ProvisionarEstabelecimentoUseCase {

    private static final Logger log =
            LoggerFactory.getLogger(ProvisionarEstabelecimentoUseCase.class);

    /**
     * Estabelecimento e primeiro administrador numa <strong>única instrução</strong>.
     *
     * <p>Um comando só é atômico sem precisar de gerenciador de transação na conexão de
     * plataforma — que, aliás, não poderia usar o da aplicação, porque ele exige um tenant que
     * ainda não existe. Duas instruções separadas abririam uma janela em que o estabelecimento
     * existe e ninguém consegue entrar nele.
     */
    private static final String PROVISIONAR = """
            with novo as (
              insert into estabelecimento
                  (nome, documento, timezone, moeda, base_comissao,
                   desconto_afeta_comissao, periodicidade_fechamento)
              values (:nome, :documento, :fuso, :moeda, :base, :descontoAfeta, :periodicidade)
              returning id
            )
            insert into usuario
                (estabelecimento_id, nome, email, email_normalizado, senha_hash, perfil)
            select novo.id, :adminNome, :adminEmail, :adminEmailNormalizado, :senhaHash, 'ADMIN'
              from novo
            returning estabelecimento_id
            """;

    private final ConexaoDeManutencao plataforma;
    private final PasswordEncoder codificadorDeSenha;

    public ProvisionarEstabelecimentoUseCase(ConexaoDeManutencao plataforma,
                                             PasswordEncoder codificadorDeSenha) {
        this.plataforma = plataforma;
        this.codificadorDeSenha = codificadorDeSenha;
    }

    public UUID executar(ProvisionarEstabelecimentoCommand comando) {
        NovoEstabelecimento novo = validar(comando);

        exigirDadosDoAdministrador(comando);

        UUID id = plataforma.jdbc().sql(PROVISIONAR)
                .param("nome", novo.nome())
                .param("documento", novo.documento())
                .param("fuso", novo.fuso().getId())
                .param("moeda", novo.moeda())
                .param("base", novo.baseComissao().name())
                .param("descontoAfeta", novo.descontoAfetaComissao())
                .param("periodicidade", novo.periodicidadeDeFechamento().name())
                .param("adminNome", comando.adminNome())
                .param("adminEmail", comando.adminEmail().trim())
                .param("adminEmailNormalizado", Emails.normalizar(comando.adminEmail()))
                .param("senhaHash", codificadorDeSenha.encode(comando.adminSenha()))
                .query(UUID.class)
                .single();

        // Sem nome nem documento no log: o cadastro de um estabelecimento traz dados de uma
        // pessoa jurídica e, no caso de MEI, de uma pessoa física.
        log.info("Estabelecimento provisionado: {} (fuso {})", id, novo.fuso());
        return id;
    }

    private void exigirDadosDoAdministrador(ProvisionarEstabelecimentoCommand comando) {
        if (comando.adminNome() == null || comando.adminNome().isBlank()
                || comando.adminEmail() == null || comando.adminEmail().isBlank()) {
            throw new ErroDeDominio(ErrosDoIam.DADOS_INVALIDOS,
                    "nome e e-mail do administrador são obrigatórios");
        }
        if (comando.adminSenha() == null || comando.adminSenha().length() < 12) {
            // 12 caracteres, sem exigência de símbolo: comprimento vale mais que composição,
            // e regra de composição empurra a pessoa para "Salao@2026", que é pior.
            throw new ErroDeDominio(ErrosDoIam.DADOS_INVALIDOS,
                    "a senha do administrador precisa de ao menos 12 caracteres");
        }
    }

    /**
     * Traduz o erro do domínio para o catálogo do módulo. O domínio não conhece código de erro nem
     * status HTTP — é o que permite testá-lo sem subir contexto.
     */
    private NovoEstabelecimento validar(ProvisionarEstabelecimentoCommand comando) {
        try {
            return NovoEstabelecimento.comFuso(
                    comando.nome(), comando.documento(), comando.fusoIana(), comando.moeda(),
                    comando.baseComissao(), comando.descontoAfetaComissao(),
                    comando.periodicidadeDeFechamento());
        } catch (DadosDoEstabelecimentoInvalidosException e) {
            throw new ErroDeDominio(ErrosDoIam.DADOS_INVALIDOS, e.getMessage());
        }
    }
}
