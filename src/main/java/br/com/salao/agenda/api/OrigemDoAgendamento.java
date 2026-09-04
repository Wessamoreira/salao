package br.com.salao.agenda.api;

/**
 * RT-AGD-002 — de onde veio a marcação.
 *
 * <p>Não é só rastro: decide o status inicial (RN-AGD-007). {@code PAINEL} é o balcão, onde a
 * recepção já falou com a cliente; {@code WEB} e {@code WHATSAPP} são a cliente sozinha, e
 * precisam de confirmação do salão.
 */
public enum OrigemDoAgendamento {
    WEB,
    WHATSAPP,
    RECORRENCIA,
    PAINEL
}
