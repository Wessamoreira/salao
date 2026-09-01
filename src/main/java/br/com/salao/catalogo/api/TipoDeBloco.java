package br.com.salao.catalogo.api;

/**
 * RT-CAT-001 — o que acontece com o tempo do profissional durante o bloco.
 *
 * <p>É a distinção que faz um sistema de salão valer a pena.
 */
public enum TipoDeBloco {

    /** O profissional está com a cliente. Não pode atender ninguém. */
    ATIVO,

    /**
     * A química age, a tinta descansa, o esmalte seca.
     *
     * <p>A cliente continua no salão — ocupando cadeira, e às vezes um recurso —, mas o
     * <strong>profissional está livre</strong> e pode atender outra pessoa.
     *
     * <p>Uma agenda que ignore isso joga fora quarenta minutos por coloração. Num sábado, é o
     * horário inteiro de outra cliente.
     */
    PAUSA;

    public boolean ocupaOProfissional() {
        return this == ATIVO;
    }
}
