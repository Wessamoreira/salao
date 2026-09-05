package br.com.salao.agenda.api;

import java.time.Instant;
import java.util.UUID;

/**
 * RT-AGD-009 — a regra que gera os agendamentos repetidos.
 *
 * <p>A recorrência é a <strong>regra</strong>; o que ela gera são agendamentos de verdade, com
 * {@code recorrencia_id} preenchido. Cancelar uma ocorrência é cancelar um agendamento comum — a
 * cliente vai viajar naquela quinta, e isso não é exceção na regra, é um cancelamento.
 */
public record Recorrencia(
        UUID id,
        UUID clienteId,
        UUID profissionalId,
        UUID servicoId,
        Frequencia frequencia,
        Instant primeiroInicio,
        boolean ativa) {
}
