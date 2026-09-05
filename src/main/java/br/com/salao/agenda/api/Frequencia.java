package br.com.salao.agenda.api;

/**
 * RT-AGD-009 — de quanto em quanto tempo a cliente volta.
 *
 * <p>Duas, e não as cinco que um seletor genérico teria. Semanal e quinzenal são o que um salão
 * usa: manutenção de raiz, escova de sexta, unha a cada quinze dias. "Toda primeira sexta do mês"
 * existe e é outra história — precisa decidir o que fazer quando o mês tem cinco sextas e quando o
 * dia 31 não existe. Está registrado como pendência em vez de resolvido pela metade.
 */
public enum Frequencia {

    SEMANAL(1),
    QUINZENAL(2);

    private final int semanas;

    Frequencia(int semanas) {
        this.semanas = semanas;
    }

    public int semanas() {
        return semanas;
    }
}
