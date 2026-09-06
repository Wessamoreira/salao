package br.com.salao.financeiro.api;

/**
 * RT-FIN-001 — com o que a comanda foi quitada.
 *
 * <p>Três grupos, e a diferença entre eles é <em>quando</em> (ou <em>se</em>) o dinheiro entra:
 *
 * <ul>
 *   <li><strong>Entra agora:</strong> {@code DINHEIRO}, {@code PIX}, {@code TRANSFERENCIA}.</li>
 *   <li><strong>Entra depois, com desconto:</strong> {@code CREDITO} e {@code DEBITO} passam pelo
 *       adquirente, que retém taxa e paga em D+N. É a diferença que faz o caixa do dono não bater
 *       com o faturamento.</li>
 *   <li><strong>Não entra:</strong> {@code CORTESIA} é o salão pagando pela cliente;
 *       {@code FIADO} é dinheiro a receber, sem data.</li>
 * </ul>
 *
 * <p>{@code CORTESIA} e {@code FIADO} são pagamentos mesmo assim — quitam a comanda. Tratá-los como
 * "não pagamento" deixaria a comanda aberta para sempre, e o salão perderia a única linha que
 * explica por que aquele atendimento não virou dinheiro.
 */
public enum FormaDePagamento {

    DINHEIRO, PIX, TRANSFERENCIA, CREDITO, DEBITO, CORTESIA, FIADO;

    /** Se passa por adquirente — e portanto tem taxa, prazo e conciliação. */
    public boolean ehCartao() {
        return this == CREDITO || this == DEBITO;
    }

    /** Se o dinheiro chega em algum momento. Cortesia, não. */
    public boolean traDinheiro() {
        return this != CORTESIA;
    }
}
