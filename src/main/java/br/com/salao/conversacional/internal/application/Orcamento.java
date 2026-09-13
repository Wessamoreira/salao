package br.com.salao.conversacional.internal.application;

import java.math.BigDecimal;

/**
 * RT-CNV-004 — o teto de custo, do ponto de vista de quem chama o modelo.
 *
 * <p>Reservar ANTES, liquidar DEPOIS (ver V53). {@link #reservar} só devolve verdadeiro se o pior caso
 * da chamada couber no que resta do dia.
 */
public interface Orcamento {

    boolean reservar(BigDecimal piorCaso);

    /** Troca a reserva pelo custo real. Chamada também quando o modelo falha, com custo zero. */
    void liquidar(BigDecimal reservado, BigDecimal real, int tokensEntrada, int tokensSaida);
}
