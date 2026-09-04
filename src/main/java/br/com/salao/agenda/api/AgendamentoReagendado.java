package br.com.salao.agenda.api;

import br.com.salao.shared.evento.EventoDeDominio;
import java.time.Instant;
import java.util.UUID;

/**
 * EV-AGD-003 — um agendamento mudou de horário, de profissional, ou dos dois.
 *
 * <p>Carrega o <strong>de</strong> e o <strong>para</strong> porque quem consome precisa dos dois:
 * {@code notificacao} escreve "seu horário mudou de 13h para 15h", e um evento que só dissesse o
 * destino obrigaria o consumidor a ter guardado a origem — que é o mesmo que não ter evento.
 *
 * <p>Só identificadores e instantes (RN-INF-009).
 */
public record AgendamentoReagendado(
        UUID estabelecimentoId,
        UUID agendamentoId,
        UUID clienteId,
        UUID profissionalAnterior,
        UUID profissionalNovo,
        Instant inicioAnterior,
        Instant inicioNovo,
        Instant fimNovo) implements EventoDeDominio {

    public boolean mudouDeProfissional() {
        return !profissionalAnterior.equals(profissionalNovo);
    }
}
