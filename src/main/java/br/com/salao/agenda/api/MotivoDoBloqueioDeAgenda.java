package br.com.salao.agenda.api;

/**
 * RT-AGD-007 — por que este pedaço da grade está tomado.
 *
 * <p>Nome longo de propósito: {@code equipe} tem o seu próprio {@code MotivoDoBloqueio}
 * (RT-EQP-003), com outra lista e outro sentido. Dois tipos com o mesmo nome em módulos diferentes
 * são um convite ao import errado, e o erro seria silencioso — compilaria e bloquearia a coisa
 * errada.
 *
 * <p>{@code MANUTENCAO} é o motivo que só existe aqui: é o do recurso quebrado, e é a razão de esta
 * rotina não ser a mesma de {@code equipe}.
 */
public enum MotivoDoBloqueioDeAgenda {

    /** O intervalo que não está na jornada porque ninguém o cadastrou como ausência. */
    ALMOCO,
    FERIAS,
    CURSO,
    FERIADO,
    /** "Preciso sair às 15h" — o bloqueio que a própria profissional desenha na grade. */
    PESSOAL,
    /** Lavatório quebrado, cadeira em conserto. Só se aplica a recurso. */
    MANUTENCAO
}
