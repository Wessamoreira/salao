package br.com.salao.agenda.api;

import br.com.salao.shared.evento.EventoDeDominio;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * RT-AGD-008 — vários agendamentos mudaram de horário de uma vez.
 *
 * <p>Um evento para o empurrão inteiro, e não um {@code AgendamentoReagendado} por atendimento. A
 * diferença aparece em {@code notificacao}: seis eventos separados viram seis mensagens para seis
 * clientes, cada uma dizendo "seu horário mudou" sem contexto. Um evento de empurrão permite ao
 * consumidor tratar o conjunto — e, principalmente, saber que a causa foi uma só.
 *
 * <p>Só identificadores e instantes (RN-INF-009).
 */
public record AgendaEmpurrada(
        UUID estabelecimentoId,
        UUID profissionalId,
        Instant aPartirDe,
        int minutos,
        List<UUID> agendamentos) implements EventoDeDominio {

    public AgendaEmpurrada {
        agendamentos = List.copyOf(agendamentos);
    }
}
