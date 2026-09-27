package br.com.salao.iam.internal.application;

import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.ConfiguracaoDoEstabelecimento;
import br.com.salao.iam.api.ErrosDoIam;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.iam.internal.infra.UsuariosJdbc;
import br.com.salao.shared.erro.CampoInvalido;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.erro.ErrosDaInfra;
import br.com.salao.shared.telefone.Telefone;
import br.com.salao.shared.telefone.TelefoneInvalidoException;
import br.com.salao.shared.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-IAM-010 — os telefones pelos quais o agente reconhece esta pessoa.
 *
 * <h2>É um gesto de permissão, e por isso é {@code usuario:manage}</h2>
 *
 * <p>Parece cadastro, e não é só isso: ligar um telefone a uma conta dá a quem escreve daquele
 * número as permissões daquela conta pelo agente. Apontar o número errado para a recepção entrega
 * a agenda do salão a um estranho. Por isso a mesma permissão de criar usuário e mudar perfil — e
 * não "qualquer um edita o próprio telefone".
 *
 * <h2>Recebe a LISTA, e não "ligar" e "desligar" separados</h2>
 *
 * <p>A tela mostra os campos e salva o que mostra; a trilha registra um {@code antes} e um
 * {@code depois}, que é a pergunta real — "quais números esta conta tinha, e quais passou a ter".
 * Dois casos de uso dariam duas linhas de auditoria para um gesto só, e quem lesse a trilha teria
 * de remontar o gesto a partir delas.
 *
 * <p>A auditoria guarda os números inteiros, e não mascarados: esta trilha existe para responder
 * "quem apontou este telefone para esta conta, e quais eram os anteriores". Com o número mascarado,
 * ela não responde a única pergunta que tem.
 *
 * <p>O teto de dois (RN-IAM-026) NÃO é conferido aqui: quem o garante é o índice único de {@code
 * V69}. Uma contagem neste método seria atravessada por duas requisições simultâneas, e a regra
 * escrita em dois lugares é a que diverge.
 */
public class DefinirTelefonesDoUsuarioUseCase {

    private final UsuariosJdbc usuarios;
    private final EstabelecimentoApi estabelecimentos;
    private final AuditoriaApi auditoria;

    public DefinirTelefonesDoUsuarioUseCase(UsuariosJdbc usuarios,
                                            EstabelecimentoApi estabelecimentos,
                                            AuditoriaApi auditoria) {
        this.usuarios = usuarios;
        this.estabelecimentos = estabelecimentos;
        this.auditoria = auditoria;
    }

    /** @param informados em qualquer formato; lista vazia tira todos os números da conta */
    @PreAuthorize("hasAuthority('" + Permissao.USUARIO_GERENCIAR + "')")
    @Transactional
    public void executar(UUID usuarioId, List<String> informados) {
        UUID tenant = TenantContext.obrigatorio();
        var atual = usuarios.porId(usuarioId).orElseThrow(() ->
                new ErroDeDominio(ErrosDaInfra.NAO_ENCONTRADO, "Usuário não encontrado."));

        List<String> telefones = normalizar(informados, tenant);

        // A checagem vem ANTES, e o `catch` fica sem consulta: depois de violar constraint o
        // Postgres aborta a transação, e buscar o dono ali falharia com 25P02 (lição de RT-EST-009).
        // Quem garante é o índice único da V69; esta consulta existe para a mensagem nomear o
        // colega — dentro do mesmo salão, onde isso não é vazamento, é orientação.
        for (String telefone : telefones) {
            usuarios.porTelefone(telefone)
                    .filter(dono -> !dono.id().equals(usuarioId))
                    .ifPresent(dono -> {
                        throw new ErroDeDominio(ErrosDoIam.TELEFONE_EM_USO,
                                "Este telefone já está na conta de " + dono.nome() + ".");
                    });
        }
        try {
            usuarios.definirTelefones(tenant, usuarioId, telefones);
        } catch (DuplicateKeyException e) {
            throw new ErroDeDominio(ErrosDoIam.TELEFONE_EM_USO,
                    "Este telefone acabou de ser ligado a outra conta.");
        }

        auditoria.registrar(RegistroDeAuditoria.alteracao("USUARIO_TELEFONE_DEFINIDO", "usuario",
                usuarioId, Map.of("telefones", atual.telefones()),
                Map.of("telefones", telefones)));
    }

    /**
     * Normaliza, tira os vazios e recusa repetido dentro do próprio pedido.
     *
     * <p>O repetido no MESMO pedido não chega ao índice único — ele é o mesmo número na mesma
     * conta, que o índice permite —, então quem recusa é aqui. Sem isto, digitar duas vezes o
     * mesmo número gastaria as duas vagas com um número só, em silêncio.
     */
    private List<String> normalizar(List<String> informados, UUID tenant) {
        if (informados == null || informados.isEmpty()) {
            return List.of();
        }
        ConfiguracaoDoEstabelecimento config = estabelecimentos.configuracao(tenant).orElseThrow();
        var telefones = new ArrayList<String>();
        for (String informado : informados) {
            if (informado == null || informado.isBlank()) {
                continue;
            }
            String e164 = normalizar(informado, config);
            if (telefones.contains(e164)) {
                throw new ErroDeDominio(ErrosDoIam.TELEFONE_EM_USO,
                        "Este telefone foi informado duas vezes.");
            }
            telefones.add(e164);
        }
        return List.copyOf(telefones);
    }

    private String normalizar(String informado, ConfiguracaoDoEstabelecimento config) {
        try {
            return Telefone.de(informado, config.paisPadrao(), config.dddPadrao()).e164();
        } catch (TelefoneInvalidoException e) {
            throw new ErroDeDominio(ErrosDoIam.TELEFONE_INVALIDO, e.getMessage(), Map.of(),
                    List.of(new CampoInvalido("telefone", "INVALIDO", e.getMessage())));
        }
    }
}
