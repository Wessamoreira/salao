package br.com.salao.financeiro.internal.domain;

/** RT-FIN-011 — sobre qual das três linhas da conferência a decisão foi tomada. */
public enum AlvoDaResolucao {

    /** Um par casado com alguma diferença. */
    PAR,

    /** Uma venda da adquirente sem pagamento no sistema — "só na maquininha". */
    VENDA,

    /** Um pagamento no cartão sem venda na maquininha — "só no sistema". */
    PAGAMENTO
}
