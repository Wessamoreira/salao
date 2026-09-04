package br.com.salao.agenda.internal.application;

import java.time.Instant;
import java.util.UUID;

/**
 * RT-AGD-003 — arrastar um agendamento na grade.
 *
 * @param profissionalId    para onde foi arrastado. Nulo mantém quem já atendia — arrastar para
 *                          cima e para baixo na mesma coluna é o movimento mais comum
 * @param duracaoMinutos    nova duração. Nulo tem regra própria: ver
 *                          {@code ReagendarUseCase.duracaoAPreservar}
 * @param versao            a versão que estava na tela. Duas recepcionistas arrastando o mesmo
 *                          bloco não podem ambas ganhar em silêncio
 */
public record PedidoDeReagendamento(
        UUID agendamentoId,
        Instant inicio,
        UUID profissionalId,
        Integer duracaoMinutos,
        int versao,
        boolean forcarForaJornada,
        boolean forcarPassado,
        String justificativa) {
}
