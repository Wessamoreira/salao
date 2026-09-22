package br.com.salao.agenda.internal.domain;

/**
 * RT-AGD-010 — o que a cliente respondeu quando a recepção ligou oferecendo a vaga.
 *
 * <p>Só {@link #ACEITOU} tira a espera da fila. Recusar é recusar <em>aquele</em> horário, e não
 * atender não diz nada sobre a espera: nos dois casos ela continua esperando a próxima vaga
 * (RN-AGD-026).
 */
public enum ResultadoDaChamada {

    /** Marcou. A chamada carrega o agendamento criado (RN-AGD-027). */
    ACEITOU,

    /** Atendeu e não quis este horário. */
    RECUSOU,

    /** Ninguém atendeu. Pode ser chamada de novo. */
    NAO_ATENDEU
}
