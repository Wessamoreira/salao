package br.com.salao.estoque.api;

/** RT-EST-006 — onde a contagem está. */
public enum StatusDoInventario {

    /** Recebendo contagens. Um por salão, garantido por índice único parcial (V46). */
    ABERTO,

    /** As divergências viraram movimento de `AJUSTE`. Não volta atrás. */
    APLICADO,

    /** Desistiu-se da contagem. Nada foi escrito no estoque. */
    CANCELADO
}
