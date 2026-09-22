package br.com.salao.agenda.internal.domain;

/** RT-AGD-010 — por que o tempo abriu (RN-AGD-009). */
public enum OrigemDaVaga {

    /** Cancelaram o horário (RT-AGD-005). */
    CANCELAMENTO,

    /** Mudaram o horário de lugar (RT-AGD-003): o antigo ficou livre. */
    REAGENDAMENTO
}
