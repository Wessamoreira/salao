package br.com.salao.shared.evento;

import br.com.salao.shared.manutencao.ConexaoDeManutencao;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * RT-INF-006 — reentrega o que ficou pendente.
 *
 * <p>Substitui o {@code republish-outstanding-events-on-restart} do Modulith, que roda no startup
 * <strong>sem tenant no escopo</strong> — e aqui toda transação exige um. Além disso, o republish
 * nativo só cobre reinício de processo; um listener que falhou com o processo de pé continuaria
 * pendente para sempre.
 *
 * <p>Percorre estabelecimento por estabelecimento, abrindo o escopo de cada um antes de reenviar
 * só as publicações daquele tenant. É o que permite ao listener abrir transação do outro lado.
 *
 * <p>A idade mínima existe para não competir com uma entrega ainda em curso: publicação recente
 * pode estar sendo processada neste instante, e reenviar seria duplicar de propósito. Entrega é
 * <em>ao menos uma vez</em> — o consumidor precisa ser idempotente de qualquer forma, mas não há
 * motivo para provocar duplicata.
 *
 * <p><strong>Limite conhecido:</strong> evento que não implemente {@link EventoDeDominio} não tem
 * como ser atribuído a um tenant e portanto nunca é reenviado. {@code ArquiteturaTest} reprova o
 * build nesse caso, para que a limitação não vire uma pendência eterna e silenciosa.
 */
public class ReenviadorDeEventos {

    private static final Logger log = LoggerFactory.getLogger(ReenviadorDeEventos.class);

    private static final String ESTABELECIMENTOS =
            "select id from estabelecimento where ativo order by criado_em";

    private final IncompleteEventPublications incompletas;
    private final ConexaoDeManutencao manutencao;
    private final Relogio relogio;
    private final Duration idadeMinima;

    public ReenviadorDeEventos(IncompleteEventPublications incompletas,
                               ConexaoDeManutencao manutencao,
                               Relogio relogio,
                               Duration idadeMinima) {
        this.incompletas = incompletas;
        this.manutencao = manutencao;
        this.relogio = relogio;
        this.idadeMinima = idadeMinima;
    }

    @Scheduled(fixedDelayString = "${app.outbox.intervalo-reenvio:PT5M}")
    public void reenviar() {
        executar(idadeMinima);
    }

    /** Exposto para teste e para acionamento manual pelo runbook. */
    public void executar(Duration idadeMinima) {
        var limite = relogio.agora().minus(idadeMinima);
        for (UUID tenant : estabelecimentos()) {
            TenantContext.executar(tenant, () -> {
                try {
                    incompletas.resubmitIncompletePublications(
                            publicacao -> pertenceA(publicacao, tenant)
                                    && publicacao.getPublicationDate().isBefore(limite));
                } catch (RuntimeException e) {
                    // Um estabelecimento com problema não pode impedir o reenvio dos outros.
                    log.error("Falha ao reenviar eventos do estabelecimento {}", tenant, e);
                }
            });
        }
    }

    private boolean pertenceA(EventPublication publicacao, UUID tenant) {
        return publicacao.getEvent() instanceof EventoDeDominio evento
                && tenant.equals(evento.estabelecimentoId());
    }

    /**
     * Pela conexão de manutenção: listar todos os estabelecimentos é cross-tenant por definição,
     * e esse é exatamente o poder que {@code salao_app} não tem (ADR-0010).
     */
    private List<UUID> estabelecimentos() {
        return manutencao.jdbc().sql(ESTABELECIMENTOS).query(UUID.class).list();
    }
}
