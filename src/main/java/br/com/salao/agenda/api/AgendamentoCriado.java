package br.com.salao.agenda.api;

import br.com.salao.shared.evento.EventoDeDominio;
import java.time.Instant;
import java.util.UUID;

/**
 * EV-AGD-001 — um agendamento foi criado.
 *
 * <p>Só identificadores e instantes, nunca nome nem telefone: o payload vive em
 * {@code event_publication}, que é infraestrutura e <strong>não tem RLS</strong> — os eventos de
 * todos os salões convivem na mesma tabela (RN-INF-009). Quem precisa do nome da cliente busca
 * pela API do módulo dono do dado.
 */
public record AgendamentoCriado(
        UUID estabelecimentoId,
        UUID agendamentoId,
        UUID clienteId,
        UUID profissionalId,
        UUID servicoId,
        Instant inicio,
        Instant fim) implements EventoDeDominio {
}
