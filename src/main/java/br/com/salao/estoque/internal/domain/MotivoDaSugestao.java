package br.com.salao.estoque.internal.domain;

/**
 * RT-EST-010 — por que o sistema acha que este preço está errado (RN-EST-031).
 *
 * <p>A ordem da declaração é a ordem de avaliação: o primeiro que se aplica é o único que a tela
 * mostra. Dois motivos no mesmo produto viram duas frases concorrentes sobre o mesmo preço.
 */
public enum MotivoDaSugestao {

    /** Vende perto do custo, ou não tem preço. É o caso que mais dói e ninguém percebe. */
    MARGEM_ABAIXO_DO_MINIMO("Margem abaixo do mínimo"),

    /** Tem saldo e não vende há tempo demais. Baixar o preço gira o que está parado. */
    ENCALHADO("Encalhado"),

    /** O custo caiu e o preço ficou alto; pode estar travando a venda. */
    MARGEM_ALTA("Margem muito acima"),

    /** A margem está sadia, mas o preço não é de balcão. */
    PRECO_QUEBRADO("Preço quebrado");

    private final String rotulo;

    MotivoDaSugestao(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }
}
