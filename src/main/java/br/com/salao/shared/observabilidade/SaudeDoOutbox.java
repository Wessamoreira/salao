package br.com.salao.shared.observabilidade;

import br.com.salao.shared.evento.MetricasDoOutbox;
import java.time.Duration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * RT-INF-008 — expõe o outbox no {@code /actuator/health}.
 *
 * <p>A métrica já existia; faltava a leitura que um humano consulta às 22h quando o cliente
 * reclama que a confirmação não chegou. Métrica responde "como está a série ao longo do tempo";
 * health responde "está quebrado agora?" — e é essa a pergunta de quem está de plantão.
 *
 * <p>Reporta {@code DOWN}, não {@code OUT_OF_SERVICE}: fila parada significa que efeitos externos
 * combinados não estão acontecendo. É falha, mesmo que a aplicação responda 200 em tudo.
 */
public class SaudeDoOutbox implements HealthIndicator {

    private final MetricasDoOutbox metricas;
    private final Duration limite;

    public SaudeDoOutbox(MetricasDoOutbox metricas, Duration limite) {
        this.metricas = metricas;
        this.limite = limite;
    }

    @Override
    public Health health() {
        double pendentes = metricas.pendentes();
        double idadeEmSegundos = metricas.idadeDaMaisAntiga();

        if (pendentes < 0 || idadeEmSegundos < 0) {
            return Health.unknown()
                    .withDetail("motivo", "não foi possível consultar o outbox")
                    .build();
        }

        var construtor = idadeEmSegundos > limite.toSeconds() ? Health.down() : Health.up();
        return construtor
                .withDetail("pendentes", (long) pendentes)
                // A idade é o que distingue fila movimentada de fila parada: um outbox
                // saudável também tem pendências a qualquer instante.
                .withDetail("idadeDaMaisAntigaEmSegundos", (long) idadeEmSegundos)
                .withDetail("limiteEmSegundos", limite.toSeconds())
                .build();
    }
}
