package br.com.salao.financeiro.api;

/**
 * RT-FIN-002 — a que a regra de comissão se aplica.
 *
 * <p>A ordem das constantes é a da <strong>cascata</strong>, do mais específico para o mais geral —
 * e {@link #peso()} a torna explícita, para nenhuma consulta precisar saber a ordem de cor.
 */
public enum EscopoDaRegra {

    /** "A Ana faz coloração por 45%." A exceção, e o nível que existe para ela. */
    PROFISSIONAL_SERVICO(3),

    /** "Coloração paga 30%: o material é caro." Vale para quem executar. */
    SERVICO(2),

    /** "O padrão da casa é 30%." O último a ser consultado, e o que evita o zero silencioso. */
    SALAO(1);

    private final int peso;

    EscopoDaRegra(int peso) {
        this.peso = peso;
    }

    /** Quanto mais alto, mais específico. Empate não existe: os escopos são disjuntos. */
    public int peso() {
        return peso;
    }
}
