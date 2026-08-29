package br.com.salao.shared.evento;

import br.com.salao.shared.manutencao.ConexaoDeManutencao;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * RT-INF-006 — as duas métricas que revelam outbox travado.
 *
 * <p>Fila travada é falha <strong>silenciosa</strong>: nenhuma exceção aparece, nenhum 5xx sobe,
 * o Sentry fica quieto — e a confirmação de agendamento simplesmente nunca chega ao cliente. É um
 * dos dois modos de falha silenciosa que o projeto tem (o outro é cache servindo dado velho), e
 * por isso o alerta prometido em 12-observabilidade é "outbox parado > 5 min".
 *
 * <p>Contagem sozinha não basta: um outbox saudável e movimentado também tem pendências a
 * qualquer instante. O que distingue movimento de paralisia é a <em>idade</em> da mais antiga.
 *
 * <p>Lê pela conexão de manutenção — a fila atravessa estabelecimentos por definição.
 */
public class MetricasDoOutbox implements MeterBinder {

    private static final String PENDENTES =
            "select count(*) from event_publication where completion_date is null";

    private static final String IDADE_MAIS_ANTIGA = """
            select coalesce(extract(epoch from now() - min(publication_date)), 0)
              from event_publication
             where completion_date is null
            """;

    private final ConexaoDeManutencao manutencao;

    public MetricasDoOutbox(ConexaoDeManutencao manutencao) {
        this.manutencao = manutencao;
    }

    @Override
    public void bindTo(MeterRegistry registro) {
        Gauge.builder("outbox.pendentes", this, MetricasDoOutbox::pendentes)
                .description("Publicações de evento ainda não concluídas")
                .register(registro);

        Gauge.builder("outbox.pendente.idade.segundos", this, MetricasDoOutbox::idadeDaMaisAntiga)
                .description("Idade da publicação pendente mais antiga; é isto que distingue "
                        + "fila movimentada de fila parada")
                .baseUnit("seconds")
                .register(registro);
    }

    /** Também consumido por {@code SaudeDoOutbox}: métrica responde "como está a série", health responde "está quebrado agora". */
    public double pendentes() {
        return consultar(PENDENTES);
    }

    public double idadeDaMaisAntiga() {
        return consultar(IDADE_MAIS_ANTIGA);
    }

    private double consultar(String sql) {
        try {
            return manutencao.jdbc().sql(sql).query(Double.class).optional().orElse(0.0);
        } catch (RuntimeException e) {
            // Métrica indisponível não pode derrubar o scrape inteiro do Actuator.
            return -1;
        }
    }
}
