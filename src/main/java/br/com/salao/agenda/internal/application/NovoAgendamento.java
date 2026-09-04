package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.api.OrigemDoAgendamento;
import java.time.Instant;
import java.util.UUID;

/**
 * RT-AGD-002 — o pedido de marcação como veio de fora.
 *
 * @param duracaoMinutos       sobrepõe a duração da receita, distribuída entre os blocos ativos
 *                             (A3). Nulo é o normal
 * @param forcarForaJornada    admite marcar fora do expediente (A4). Exige justificativa
 * @param forcarPassado        admite marcar no passado — a recepção lançando o que ficou no papel
 *                             (RN-AGD-008). Exige justificativa
 * @param justificativa        obrigatória com qualquer um dos dois forçamentos, e vai para a
 *                             trilha: forçar sem dizer por quê é a mesma coisa que não ter regra
 * @param chaveIdempotencia    o {@code Idempotency-Key}. Nulo desliga — mas o duplo clique no
 *                             botão de marcar é real, e sem chave ele cria dois agendamentos
 */
public record NovoAgendamento(
        UUID clienteId,
        UUID profissionalId,
        UUID servicoId,
        Instant inicio,
        OrigemDoAgendamento origem,
        String observacao,
        Integer duracaoMinutos,
        boolean forcarForaJornada,
        boolean forcarPassado,
        String justificativa,
        String chaveIdempotencia) {
}
