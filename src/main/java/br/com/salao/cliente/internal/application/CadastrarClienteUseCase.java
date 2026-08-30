package br.com.salao.cliente.internal.application;

import br.com.salao.cliente.api.ErrosDoCliente;
import br.com.salao.cliente.internal.domain.Nomes;
import br.com.salao.cliente.internal.domain.Telefone;
import br.com.salao.cliente.internal.domain.TelefoneInvalidoException;
import br.com.salao.cliente.internal.infra.ClientesJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.ConfiguracaoDoEstabelecimento;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.CampoInvalido;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tenant.TenantContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CLI-001 — cadastrar cliente.
 *
 * <h2>Um caminho só, não dois</h2>
 *
 * <p>O cadastro rápido (nome + telefone, R-UX-04) e o cadastro completo são <strong>o mesmo</strong>
 * caso de uso, porque a diferença entre eles é só quantos campos vieram preenchidos. Dois caminhos
 * separados divergiriam na primeira regra nova — e a regra acabaria valendo só no completo,
 * justamente o menos usado.
 *
 * <h2>Telefone é opcional, e isso é decisão de negócio</h2>
 *
 * <p>Cliente que passa e pergunta o preço, criança que vem com a mãe, a senhora que não tem
 * celular: existem e precisam de ficha. Exigir telefone faria a recepção digitar
 * {@code 00000-0000}, que é pior que o vazio — parece dado e não é.
 */
public class CadastrarClienteUseCase {

    private static final Logger log = LoggerFactory.getLogger(CadastrarClienteUseCase.class);

    private final ClientesJdbc clientes;
    private final EstabelecimentoApi estabelecimentos;
    private final AuditoriaApi auditoria;

    public CadastrarClienteUseCase(ClientesJdbc clientes, EstabelecimentoApi estabelecimentos,
                                   AuditoriaApi auditoria) {
        this.clientes = clientes;
        this.estabelecimentos = estabelecimentos;
        this.auditoria = auditoria;
    }

    @PreAuthorize("hasAuthority('" + Permissao.CLIENTE_ESCREVER + "')")
    @Transactional
    public UUID executar(DadosDoCliente dados) {
        UUID tenant = TenantContext.obrigatorio();

        String nome = Nomes.opcional(dados.nome());
        if (nome == null) {
            throw new ErroDeDominio(ErrosDoCliente.NOME_OBRIGATORIO,
                    "o nome do cliente é obrigatório", Map.of(),
                    List.of(new CampoInvalido("nome", "OBRIGATORIO", "Informe o nome")));
        }

        String telefone = normalizarTelefone(dados.telefone(), tenant);

        UUID id = clientes.inserir(tenant, nome, Nomes.opcional(dados.apelido()), telefone,
                Nomes.opcional(dados.email()), Nomes.opcional(dados.observacao()),
                UsuarioAtual.idOuNulo());

        auditoria.registrar(RegistroDeAuditoria.criacao("cliente", id, resumoParaAuditoria(nome)));
        log.info("Cliente cadastrado: {}", id);
        return id;
    }

    /**
     * O telefone é normalizado com o DDD do estabelecimento — ver {@link Telefone}.
     *
     * <p>A configuração vem por {@link EstabelecimentoApi}, nunca por join: é a fronteira de
     * módulo que torna a extração futura barata.
     */
    private String normalizarTelefone(String informado, UUID tenant) {
        if (informado == null || informado.isBlank()) {
            return null;
        }
        ConfiguracaoDoEstabelecimento config = estabelecimentos.configuracao(tenant).orElseThrow();
        try {
            return Telefone.de(informado, config.paisPadrao(), config.dddPadrao()).e164();
        } catch (TelefoneInvalidoException e) {
            // O detalhe do domínio explica o caso concreto e vai para o campo, que é onde o front
            // consegue apontar o erro sem adivinhar.
            throw new ErroDeDominio(ErrosDoCliente.TELEFONE_INVALIDO, e.getMessage(), Map.of(),
                    List.of(new CampoInvalido("telefone", "INVALIDO", e.getMessage())));
        }
    }

    /**
     * Só o nome vai para a trilha.
     *
     * <p>Auditoria é retida por prazo longo e consultada por muita gente: copiar telefone e
     * e-mail para dentro dela criaria uma segunda base de dados pessoais fora do alcance da
     * anonimização da LGPD (RT-CLI-005), que só sabe limpar a tabela {@code cliente}.
     */
    private Map<String, Object> resumoParaAuditoria(String nome) {
        Map<String, Object> resumo = new HashMap<>();
        resumo.put("nome", nome);
        return resumo;
    }
}
