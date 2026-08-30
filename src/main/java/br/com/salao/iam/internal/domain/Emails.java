package br.com.salao.iam.internal.domain;

import java.util.Locale;

/**
 * RT-IAM-002 — normalização de e-mail para login.
 *
 * <p>{@code Locale.ROOT} é deliberado: com o locale turco, {@code "I".toLowerCase()} produz "ı"
 * (i sem ponto), e dois e-mails que deveriam colidir passariam pelo índice único. É um bug que
 * só aparece na máquina de alguém com outra configuração regional — exatamente o tipo que
 * ninguém encontra revisando código.
 */
public final class Emails {

    private Emails() {
    }

    public static String normalizar(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
