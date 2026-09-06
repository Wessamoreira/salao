package br.com.salao.estoque.api;

/**
 * RT-EST-002 — o que aconteceu com as unidades.
 *
 * <p>O tipo é <strong>rótulo</strong>, não regra de conta: quem soma olha o sinal da quantidade.
 * Fosse o tipo a decidir entrada ou saída, toda consulta precisaria conhecer a lista — e a primeira
 * que esquecesse um tipo novo daria um número plausível e errado.
 */
public enum TipoDeMovimento {

    /** Chegou (RT-EST-002). Sempre positiva. */
    ENTRADA,

    /** Saiu numa comanda (RT-EST-004). */
    VENDA,

    /** Some dentro do serviço: tinta, ox (RT-EST-004 e RT-EST-007). */
    CONSUMO_INTERNO,

    /** Quebrou, venceu, sumiu (RT-EST-005). */
    PERDA,

    /** Correção de contagem, com motivo (RT-EST-005 e RT-EST-006). */
    AJUSTE,

    /** Voltou para o fornecedor, ou a cliente devolveu (RT-EST-005). */
    DEVOLUCAO
}
