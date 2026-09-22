package br.com.salao.estoque.internal.domain;

/**
 * RT-EST-010 — por que o sistema acha que este preço está errado (RN-EST-031).
 *
 * <p>A ordem da declaração é a ordem de avaliação: o primeiro que se aplica é o único que a tela
 * mostra. Dois motivos no mesmo produto viram duas frases concorrentes sobre o mesmo preço.
 */
public enum MotivoDaSugestao {

    /**
     * RT-EST-012 — o lote vence em até 30 dias. Vem antes de tudo: é dinheiro que vira zero numa data
     * marcada. E é o único motivo cujo chão desce até o custo médio (RN-EST-037).
     */
    QUEIMA_DE_VENCIMENTO("Queima de vencimento"),

    /** Vende perto do custo, ou não tem preço. É o caso que mais dói e ninguém percebe. */
    MARGEM_ABAIXO_DO_MINIMO("Margem abaixo do mínimo"),

    /** Tem saldo e não vende há tempo demais. Baixar o preço gira o que está parado. */
    ENCALHADO("Encalhado"),

    /** O custo caiu e o preço ficou alto; pode estar travando a venda. */
    MARGEM_ALTA("Margem muito acima"),

    /** A margem está sadia, mas o preço não é de balcão. */
    PRECO_QUEBRADO("Preço quebrado");

    private final String rotulo;

    /** Se o chão deste motivo é o custo, e não o custo mais a margem mínima. */
    public boolean chaoNoCusto() {
        return this == QUEIMA_DE_VENCIMENTO;
    }

    MotivoDaSugestao(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }
}
