package br.com.salao.iam.internal.web;

import java.time.Instant;

/**
 * RT-IAM-005 — resposta do login quando o usuário tem segundo fator.
 *
 * <p>{@code segundoFatorNecessario} é redundante para quem lê o JSON com atenção — a ausência de
 * {@code tokenDeAcesso} já diria —, e existe justamente por isso: o front não deve inferir estado
 * pela ausência de um campo. Um `if (!resposta.tokenDeAcesso)` quebra em silêncio no dia em que a
 * resposta mudar de forma.
 */
public record DesafioResponse(String desafio, Instant expiraEm, boolean segundoFatorNecessario) {

    public DesafioResponse(String desafio, Instant expiraEm) {
        this(desafio, expiraEm, true);
    }
}
