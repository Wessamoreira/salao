package br.com.salao.shared.observabilidade;

import br.com.salao.shared.cache.OuvinteDeInvalidacao;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * RT-INF-008 — expõe o ouvinte de invalidação no {@code /actuator/health}.
 *
 * <p>Com o ouvinte fora, a aplicação continua respondendo 200 em tudo e servindo preço velho por
 * até 30 minutos. É o modo de falha mais traiçoeiro do sistema: nada indica que algo está errado,
 * exceto isto aqui e a métrica {@code cache.listener.up}.
 */
public class SaudeDoOuvinteDeCache implements HealthIndicator {

    private final OuvinteDeInvalidacao ouvinte;

    public SaudeDoOuvinteDeCache(OuvinteDeInvalidacao ouvinte) {
        this.ouvinte = ouvinte;
    }

    @Override
    public Health health() {
        if (ouvinte.conectado()) {
            return Health.up().withDetail("canal", "cache_invalidacao").build();
        }
        return Health.down()
                .withDetail("efeito", "invalidações estão sendo perdidas; o cache local depende "
                        + "apenas do expireAfterWrite de 30 min")
                .build();
    }
}
