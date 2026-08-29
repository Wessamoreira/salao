package br.com.salao.shared.observabilidade;

import br.com.salao.shared.cache.OuvinteDeInvalidacao;
import br.com.salao.shared.evento.MetricasDoOutbox;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** RT-INF-008 — fiação da observabilidade. */
@Configuration(proxyBeanMethods = false)
public class ObservabilidadeConfig {

    /** Nome do bean define a chave no {@code /actuator/health}: {@code outbox}. */
    @Bean("outboxHealthIndicator")
    public HealthIndicator saudeDoOutbox(
            MetricasDoOutbox metricas,
            @Value("${app.observabilidade.outbox-parado-apos:PT5M}") Duration limite) {
        return new SaudeDoOutbox(metricas, limite);
    }

    @Bean("ouvinteDeCacheHealthIndicator")
    public HealthIndicator saudeDoOuvinteDeCache(OuvinteDeInvalidacao ouvinte) {
        return new SaudeDoOuvinteDeCache(ouvinte);
    }
}
