package br.com.salao.financeiro.internal.application;

import br.com.salao.financeiro.internal.domain.Casador;
import br.com.salao.financeiro.internal.infra.ConciliacaoJdbc;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.shared.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-010 — uma rodada de casamento: primeiro o que foi cancelado depois de casado, depois os pares
 * novos.
 *
 * <p>Não é caso de uso: não tem porta. Quem chama é a importação, no fim da mesma transação, e o botão
 * "Conciliar agora" — os dois com {@code financeiro:reconcile} conferido. {@code MANDATORY} garante que
 * ninguém o chama fora de uma delas.
 */
public class Conciliador {

    private static final Logger log = LoggerFactory.getLogger(Conciliador.class);

    /** Decisão do dono (19/09/2026): até duas horas entre o registro no sistema e a venda. */
    public static final Duration JANELA = Duration.ofHours(2);

    private final ConciliacaoJdbc conciliacao;
    private final EstabelecimentoApi estabelecimentos;

    public Conciliador(ConciliacaoJdbc conciliacao, EstabelecimentoApi estabelecimentos) {
        this.conciliacao = conciliacao;
        this.estabelecimentos = estabelecimentos;
    }

    /**
     * @param casadas            pares novos nesta rodada
     * @param divergentes        dos novos, quantos com alguma diferença
     * @param canceladasDepois pares antigos cuja venda foi cancelada na adquirente desde a última rodada
     */
    public record Rodada(int casadas, int divergentes, int canceladasDepois) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Rodada conciliar(Instant agora) {
        ZoneId fuso = estabelecimentos.fusoDe(TenantContext.obrigatorio());
        int canceladasDepois = conciliacao.marcarCanceladas();
        var pares = Casador.casar(conciliacao.pagamentosACasar(fuso), conciliacao.vendasACasar(),
                JANELA, fuso);
        int casadas = 0;
        int divergentes = 0;
        for (Casador.Par par : pares) {
            if (conciliacao.gravar(par, agora)) {
                casadas++;
                if (par.divergente()) {
                    divergentes++;
                }
            }
        }
        log.info("Conciliação: {} casadas ({} divergentes); {} canceladas na adquirente depois de "
                + "casadas", casadas, divergentes, canceladasDepois);
        return new Rodada(casadas, divergentes, canceladasDepois);
    }
}
