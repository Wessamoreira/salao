package br.com.salao.agenda.api;

import java.time.Instant;
import java.util.UUID;

/**
 * RT-AGD-001 — um horário que pode ser oferecido à cliente.
 *
 * <p>{@code fim} é quando a cliente vai embora, e {@code minutosDoProfissional} é o que aquele
 * horário custa da agenda de quem atende. Numa coloração os dois são 100 e 60 — e é a diferença
 * entre os dois que o salão vende para outra pessoa.
 *
 * <p>Traz o profissional porque a consulta aceita "qualquer um": sem isso, quem recebeu a lista
 * não saberia com quem marcar.
 */
public record HorarioDisponivel(
        Instant inicio,
        Instant fim,
        UUID profissionalId,
        String profissional,
        int minutosDoProfissional) {
}
