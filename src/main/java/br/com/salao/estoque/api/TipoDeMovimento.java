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

    /**
     * A cliente devolveu: as unidades voltam aos lotes de onde saíram (RT-ATD-007).
     *
     * <p>Quantidade <strong>positiva</strong>. Escrita pelo estorno e pela reabertura de comanda,
     * nunca à mão.
     */
    DEVOLUCAO,

    /**
     * Mandamos de volta ao fornecedor (RT-EST-005). Quantidade negativa.
     *
     * <p>Separado de {@link #DEVOLUCAO} porque é outro fato: o estoque sai em vez de entrar, a
     * contraparte é o fornecedor e não a cliente, e "quanto devolvemos ao fornecedor este mês?" é
     * pergunta de compras. Com um tipo só, ela dependeria de filtrar por sinal — e de ninguém
     * nunca ter misturado os dois.
     *
     * <p>É a mesma razão pela qual o razão separa {@code RECEITA_SERVICO} de
     * {@code RECEITA_PRODUTO}: mesmo sinal, significados diferentes. Aqui os sinais são opostos.
     */
    DEVOLUCAO_AO_FORNECEDOR
}
