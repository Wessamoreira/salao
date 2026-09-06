package br.com.salao.atendimento.api;

/**
 * RT-ATD-002 — o que a linha da comanda cobra.
 *
 * <p>A diferença não é de nomenclatura: serviço tem executante e comissão, produto tem lote, custo
 * e baixa de estoque. São dois caminhos distintos na mesma tabela, e o {@code check} do banco
 * garante que cada linha tenha a referência do seu tipo.
 */
public enum TipoDeItem {

    /** Trabalho executado por alguém. Gera comissão (RT-FIN-003). */
    SERVICO,

    /** Coisa vendida. Sai do estoque e carrega o custo do lote (RT-ATD-003). */
    PRODUTO
}
