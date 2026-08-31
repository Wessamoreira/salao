package br.com.salao.equipe.api;

/**
 * RT-EQP-003 — por que o profissional (ou o salão) não atende.
 *
 * <p>Todos removem disponibilidade da mesma forma; o motivo existe para quem lê a agenda entender
 * o buraco. "Não atende" sem explicação faz a recepção ligar para perguntar.
 */
public enum MotivoDoBloqueio {

    /** Fecha o salão inteiro, e por isso costuma vir sem profissional. */
    FERIADO,
    FERIAS,
    CURSO,
    ATESTADO,
    /** Folga combinada fora da jornada semanal — troca de dia, compensação. */
    FOLGA,
    OUTRO
}
