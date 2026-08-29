package br.com.salao.shared.tempo;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** RN-INF-006 — implementação de produção. Único lugar autorizado a ler o relógio real. */
@Component
public class RelogioDoSistema implements Relogio {

    private final Clock clock = Clock.systemUTC();

    @Override
    public Instant agora() {
        return clock.instant();
    }
}
