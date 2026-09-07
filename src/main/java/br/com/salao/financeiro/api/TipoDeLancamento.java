package br.com.salao.financeiro.api;

/**
 * RT-FIN-003 — o que a linha do razão representa.
 *
 * <p>O tipo é <strong>rótulo</strong>; quem soma olha o sinal do valor. É a mesma decisão do
 * movimento de estoque (RN-EST-007), pela mesma razão: se o tipo decidisse entrada e saída, toda
 * consulta precisaria conhecer a lista, e a primeira que esquecesse um tipo novo daria um número
 * plausível e errado.
 */
public enum TipoDeLancamento {

    /** Entrou pelo serviço executado. Positivo. */
    RECEITA_SERVICO,

    /** Entrou pela venda de produto. Positivo. */
    RECEITA_PRODUTO,

    /** Devido a quem executou. Negativo — sai do salão. */
    COMISSAO,

    /** Retido pelo adquirente. Negativo. Chega na Fase 5. */
    TAXA_ADQUIRENTE,

    /** Adiantamento ao profissional (RT-FIN-005). */
    VALE,

    /** O que foi efetivamente pago no fechamento do período (RT-FIN-006). */
    REPASSE,

    /** Correção manual, com motivo. */
    AJUSTE,

    /** Linha que anula outra, apontando para ela. Nunca se apaga a original. */
    ESTORNO
}
