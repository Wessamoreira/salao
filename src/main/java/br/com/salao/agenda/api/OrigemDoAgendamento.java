package br.com.salao.agenda.api;

/**
 * RT-AGD-002 — de onde veio a marcação.
 *
 * <p>Não é só rastro: decide o status inicial (RN-AGD-013). {@code PAINEL} é o balcão, onde a
 * recepção já falou com a cliente; {@code WEB} e {@code WHATSAPP} são a cliente sozinha, e
 * precisam de confirmação do salão.
 *
 * <p>{@code AGENTE} (RT-CNV-005) é alguém da equipe marcando pelo agente conversacional, depois de
 * confirmar o resumo com o código. Do lado do salão, como o balcão — e distinto dele, para a agenda
 * saber que a marcação veio do agente.
 */
public enum OrigemDoAgendamento {
    WEB,
    WHATSAPP,
    RECORRENCIA,
    PAINEL,
    AGENTE
}
