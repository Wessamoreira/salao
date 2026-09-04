package br.com.salao.agenda.api;

import br.com.salao.shared.evento.EventoDeDominio;
import java.time.Instant;
import java.util.UUID;

/**
 * EV-AGD-002 — o agendamento foi confirmado.
 *
 * <p>{@code confirmadoPelaCliente} distingue os dois caminhos que levam ao mesmo status: a cliente
 * respondendo ao lembrete e o salão confirmando por ela. Quem consome precisa da diferença —
 * mandar "obrigado por confirmar" para quem não confirmou nada é pior do que não mandar nada.
 *
 * <p>Só identificadores e instantes (RN-INF-009).
 */
public record AgendamentoConfirmado(
        UUID estabelecimentoId,
        UUID agendamentoId,
        UUID clienteId,
        UUID profissionalId,
        Instant inicio,
        boolean confirmadoPelaCliente) implements EventoDeDominio {
}
