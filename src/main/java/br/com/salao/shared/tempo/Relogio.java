package br.com.salao.shared.tempo;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * RN-INF-006 — a única fonte de "agora" da aplicação.
 *
 * <p>{@code Instant.now()} espalhado torna regra de tempo impossível de testar sem
 * {@code Thread.sleep}. Regras como "cancelamento com menos de 24h cobra taxa" ou "não se agenda
 * no passado" (RN-AGD-008) só são testáveis de verdade com o relógio injetado.
 *
 * <p>{@code ArquiteturaTest.instante_nunca_vem_de_now} garante que ninguém contorne.
 */
public interface Relogio {

    Instant agora();

    /**
     * O dia civil no fuso <strong>do estabelecimento</strong> (ADR-0009).
     *
     * <p>Nunca use o fuso padrão da JVM: o servidor roda em UTC e o salão pode estar em qualquer
     * fuso — a aplicação é multi-tenant desde o dia 0.
     */
    default LocalDate hojeEm(ZoneId fusoDoEstabelecimento) {
        return agora().atZone(fusoDoEstabelecimento).toLocalDate();
    }
}
