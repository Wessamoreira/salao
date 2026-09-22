package br.com.salao.agenda.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * RT-AGD-010 — uma ligação da recepção para quem espera, como foi gravada.
 *
 * @param agendamentoId o horário marcado — presente se e só se ela aceitou (RN-AGD-027)
 * @param motivoDoPulo  por que ela foi chamada antes de alguém na ordem (RN-AGD-026); nulo sem pulo
 * @param registradaPor quem registrou; nulo quando não se sabe
 */
public record ChamadaDaFila(UUID id, UUID vagaId, UUID esperaId, ResultadoDaChamada resultado,
                            UUID agendamentoId, String motivoDoPulo, Instant registradaEm,
                            UUID registradaPor) {
}
