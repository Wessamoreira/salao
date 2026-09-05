package br.com.salao.agenda.api;

import br.com.salao.shared.evento.EventoDeDominio;
import java.time.Instant;
import java.util.UUID;

/**
 * EV-AGD-004 — o agendamento foi cancelado, e o horário voltou para a agenda.
 *
 * <p>Carrega a <strong>janela liberada</strong> — profissional, serviço, início e fim — e não só o
 * id: é exatamente o que a fila de espera (RT-AGD-010) precisa para achar quem cabe ali. Um evento
 * que só dissesse "cancelado" obrigaria o consumidor a buscar o agendamento de volta, e ele acabou
 * de deixar de existir para a agenda.
 *
 * <p>Por isso <strong>não há um {@code VagaLiberada} separado</strong>: seriam dois eventos com o
 * mesmo conteúdo disparados no mesmo instante. Está registrado em RT-AGD-005 para quem for
 * implementar a fila.
 *
 * <p>Só identificadores e instantes (RN-INF-009). O motivo do cancelamento é texto livre, escrito
 * por gente, e pode conter qualquer coisa — fica na tabela, fora do payload.
 */
public record AgendamentoCancelado(
        UUID estabelecimentoId,
        UUID agendamentoId,
        UUID clienteId,
        UUID profissionalId,
        UUID servicoId,
        Instant inicio,
        Instant fim,
        boolean canceladoPelaCliente) implements EventoDeDominio {
}
