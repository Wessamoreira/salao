package br.com.salao.financeiro.internal.application;

import br.com.salao.financeiro.api.ErrosDoFinanceiro;
import br.com.salao.financeiro.api.RazaoApi;
import br.com.salao.financeiro.api.Vale;
import br.com.salao.financeiro.internal.infra.ValesJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-005 — cancelar um vale.
 *
 * <h2>Cancelar é estornar, e o vale continua lá</h2>
 *
 * <p>Nada é apagado: a role da aplicação não tem {@code delete} em {@code vale} nem em
 * {@code lancamento} (V40, V41). O cancelamento é uma linha nova de sinal contrário apontando para
 * a original — e é ela que faz {@code cancelado} ser verdade, sem coluna de status para manter em
 * dia.
 *
 * <p>Que o vale errado continue visível não é efeito colateral: <em>"por que a Ana recebeu R$ 300
 * em março?"</em> precisa continuar tendo resposta depois de o registro ser desfeito, e um vale
 * apagado responderia "não recebeu".
 *
 * <h2>Idempotente, e sem erro na segunda vez</h2>
 *
 * <p>Cancelar duas vezes devolve o mesmo vale cancelado, e não uma recusa. Quem repete o pedido
 * quer o mesmo estado final, e recusar obrigaria a tela a distinguir "não deu certo" de "já
 * estava" — distinção que ela erraria, porque o resultado é o mesmo.
 *
 * <p>A idempotência vem do próprio razão (RN-FIN-010): quem já foi estornado tem uma linha
 * apontando para si, e {@code estornarOrigem} não escreve de novo.
 */
public class CancelarValeUseCase {

    private static final Logger log = LoggerFactory.getLogger(CancelarValeUseCase.class);

    private final ValesJdbc vales;
    private final RazaoApi razao;
    private final AuditoriaApi auditoria;

    public CancelarValeUseCase(ValesJdbc vales, RazaoApi razao, AuditoriaApi auditoria) {
        this.vales = vales;
        this.razao = razao;
        this.auditoria = auditoria;
    }

    @PreAuthorize("hasAuthority('" + Permissao.FINANCEIRO_ADIANTAR + "')")
    @Transactional
    public Vale executar(UUID valeId, String motivo) {
        if (motivo == null || motivo.isBlank()) {
            // O motivo é o que separa correção de desfazimento silencioso — mesma exigência da
            // reabertura e do estorno de comanda (RT-ATD-007).
            throw new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS,
                    "informe o motivo do cancelamento");
        }
        Vale vale = vales.porId(valeId)
                .orElseThrow(() -> new ErroDeDominio(ErrosDoFinanceiro.VALE_NAO_ENCONTRADO,
                        "vale " + valeId + " não encontrado"));

        int anulados = razao.estornarOrigem(RegistrarValeUseCase.ORIGEM_VALE, valeId, motivo);
        if (anulados > 0) {
            auditoria.registrar(RegistroDeAuditoria.alteracao("VALE_CANCELADO", "vale", valeId,
                    Map.of("valor", vale.valor().toString(), "forma", vale.forma().name()),
                    Map.of("motivo", motivo)));
            log.warn("Vale {} cancelado: {}", valeId, motivo);
        }
        return vales.porId(valeId).orElseThrow();
    }
}
