package br.com.salao.agenda.api;

import br.com.salao.shared.evento.EventoDeDominio;
import java.time.Instant;
import java.util.UUID;

/**
 * EV-AGD-005 — a cliente não apareceu.
 *
 * <p>Interno, consumido por {@code cliente} para o histórico. É o dado que sustenta a conversa mais
 * difícil do balcão — <em>"esta cliente faltou três vezes"</em> — e por isso precisa chegar lá em
 * vez de ficar só na agenda: quem atende no balcão está olhando a ficha da pessoa, não a grade de
 * duas semanas atrás.
 *
 * <p>{@code minutosPerdidos} é o tempo de profissional que ficou vago — não a duração total. Numa
 * coloração de 100 minutos, 40 eram pausa e a profissional estaria livre de qualquer jeito: contar
 * os 100 inflaria o prejuízo e tiraria a credibilidade do número justamente na conversa em que ele
 * precisa ser levado a sério.
 *
 * <p>Só identificadores e instantes (RN-INF-009).
 */
public record NoShowRegistrado(
        UUID estabelecimentoId,
        UUID agendamentoId,
        UUID clienteId,
        UUID profissionalId,
        UUID servicoId,
        Instant inicio,
        int minutosPerdidos) implements EventoDeDominio {
}
