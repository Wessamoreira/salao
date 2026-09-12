package br.com.salao.financeiro.internal.application;

import br.com.salao.financeiro.api.ErrosDoFinanceiro;
import br.com.salao.financeiro.api.FechamentoDoProfissional;
import br.com.salao.financeiro.internal.infra.FechamentosJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-007 — desfazer um fechamento e devolver o período.
 *
 * <h2>O documento fica; o que sai é a reivindicação do período</h2>
 *
 * <p>`fechamento` continua imutável nos campos que dizem quanto foi pago — a V43 concede
 * {@code update} em <strong>uma</strong> coluna, {@code estornado_em}, e o teste de RN-FIN-020
 * continua provando que {@code valor_repassado} é recusado pelo banco.
 *
 * <p>Apagar a linha seria perder o registro de que o pagamento existiu, que é exatamente o que se
 * precisa para explicar o dinheiro que saiu da conta. O documento passa a ser histórico: ele conta
 * que em tal dia pagou-se X e depois anulou-se.
 *
 * <h2>Estornar não devolve dinheiro; devolve dívida</h2>
 *
 * <p>O dinheiro já saiu da conta. O que a anulação faz é recolocar o valor na conta do
 * profissional: ele volta a ter aquilo a receber, e o fechamento seguinte paga de novo. Se o
 * repasse foi feito por engano e o dinheiro não voltou de fato, o caminho é outro — um vale, que é
 * o que registra dinheiro entregue fora do repasse.
 *
 * <h2>Por dentro do mesmo lock do fechamento</h2>
 *
 * <p>Estornar e fechar disputam o mesmo saldo acumulado. Sem o lock, um estorno concorrente com um
 * fechamento poderia liberar o período depois da conferência e antes da gravação.
 */
public class EstornarFechamentoUseCase {

    private static final Logger log = LoggerFactory.getLogger(EstornarFechamentoUseCase.class);

    static final String ORIGEM_FECHAMENTO = "FECHAMENTO";

    private final FechamentosJdbc fechamentos;
    private final Anulacao anulacao;
    private final AuditoriaApi auditoria;
    private final Relogio relogio;

    public EstornarFechamentoUseCase(FechamentosJdbc fechamentos, Anulacao anulacao,
                                     AuditoriaApi auditoria, Relogio relogio) {
        this.fechamentos = fechamentos;
        this.anulacao = anulacao;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    @PreAuthorize("hasAuthority('" + Permissao.FINANCEIRO_FECHAR + "')")
    @Transactional
    public FechamentoDoProfissional executar(UUID fechamentoId, String motivo) {
        if (motivo == null || motivo.isBlank()) {
            // O motivo é o que separa correção de desfazimento silencioso — mesma exigência da
            // reabertura de comanda (RT-ATD-007) e do cancelamento de vale (RN-FIN-013).
            throw new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS,
                    "informe o motivo do estorno");
        }
        UUID tenant = TenantContext.obrigatorio();
        fechamentos.travarEstabelecimento(tenant);

        FechamentoDoProfissional documento = fechamentos.porId(fechamentoId)
                .orElseThrow(() -> new ErroDeDominio(ErrosDoFinanceiro.FECHAMENTO_NAO_ENCONTRADO,
                        "fechamento " + fechamentoId + " não encontrado"));

        // Idempotente: repetir devolve o mesmo estado, sem sobrescrever a data do primeiro
        // estorno. Quem repete quer o mesmo resultado final, e recusar obrigaria a tela a
        // distinguir "não deu certo" de "já estava".
        if (!documento.vigente()) {
            return documento;
        }

        int anulados = anulacao.deOrigem(tenant, ORIGEM_FECHAMENTO, fechamentoId, motivo);
        fechamentos.marcarEstornado(fechamentoId, relogio.agora());

        auditoria.registrar(RegistroDeAuditoria.alteracao("FECHAMENTO_ESTORNADO", "fechamento",
                fechamentoId,
                Map.of("profissionalId", String.valueOf(documento.profissionalId()),
                        "periodo", documento.periodoInicio() + " a " + documento.periodoFim(),
                        "repassado", documento.valorRepassado().toString()),
                Map.of("motivo", motivo, "linhasAnuladas", String.valueOf(anulados))));

        log.warn("Fechamento {} de {} a {} estornado ({} linha(s)): {}", fechamentoId,
                documento.periodoInicio(), documento.periodoFim(), anulados, motivo);
        return fechamentos.porId(fechamentoId).orElseThrow();
    }
}
