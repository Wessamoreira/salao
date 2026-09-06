package br.com.salao.estoque.api;

/**
 * RT-EST-001 — para que o produto serve no salão.
 *
 * <p>A mesma entidade, com finalidades diferentes: o shampoo que a cliente leva para casa e a tinta
 * que a coloração gasta. E o mesmo frasco pode ser as duas coisas.
 *
 * <p>Separar em duas tabelas obrigaria a cadastrar duas vezes o que é um produto só, e o saldo se
 * dividiria entre elas — o frasco vendido não baixaria do saldo que a coloração usa.
 */
public enum FinalidadeDoProduto {

    /** Vai para a sacola da cliente. Precisa de preço para ser vendido. */
    VENDA,

    /** Some dentro do serviço. Nunca tem preço de venda — o que não se vende não tem preço. */
    CONSUMO_INTERNO,

    /** Os dois: o mesmo frasco de shampoo lava aqui e é vendido no balcão. */
    AMBOS;

    /** Se este produto pode ir para uma comanda. */
    public boolean podeSerVendido() {
        return this != CONSUMO_INTERNO;
    }
}
