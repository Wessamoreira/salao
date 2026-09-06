package br.com.salao.atendimento.api;

/**
 * RT-ATD-002 — com que papel a pessoa entrou no item.
 *
 * <p>É o que resolve o assistente que lava o cabelo sem inventar um segundo item: uma linha a mais
 * no mesmo serviço, com percentual próprio.
 */
public enum PapelDoExecutante {

    /** Quem executou o serviço. Todo item de serviço tem exatamente um. */
    PRINCIPAL,

    /** Quem ajudou, com percentual próprio. Chega em RT-ATD-005. */
    ASSISTENTE
}
