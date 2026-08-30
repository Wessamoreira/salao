package br.com.salao.iam.internal.web;

import br.com.salao.iam.api.SessaoIniciada;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.ResponseCookie;

/**
 * RT-IAM-003 — o refresh viaja em cookie, nunca no corpo.
 *
 * <p>Quatro atributos, e cada um responde a um ataque diferente:
 *
 * <ul>
 *   <li><b>{@code HttpOnly}</b> — JavaScript não lê. Um XSS consegue usar a sessão enquanto a
 *       página está aberta, mas não consegue <em>levar embora</em> o token de trinta dias;
 *   <li><b>{@code Secure}</b> — não trafega em texto claro;
 *   <li><b>{@code SameSite=Strict}</b> — não acompanha requisição vinda de outro site, que é a
 *       proteção contra CSRF neste desenho;
 *   <li><b>{@code Path=/api/v1/auth}</b> — não é enviado para os demais endpoints. Reduz a
 *       superfície: o token só aparece onde é usado.
 * </ul>
 *
 * <p>É por isso que o access token vive em memória no front e o refresh vive aqui. Guardar
 * qualquer um dos dois em {@code localStorage} seria XSS servido de bandeja.
 */
public final class CookieDeRefresh {

    public static final String NOME = "salao_refresh";
    private static final String CAMINHO = "/api/v1/auth";

    private CookieDeRefresh() {
    }

    public static ResponseCookie de(SessaoIniciada sessao, boolean seguro, Instant agora) {
        return base(sessao.refresh(), seguro)
                .maxAge(Duration.between(agora, sessao.refreshExpiraEm()))
                .build();
    }

    /** Cookie vazio e vencido: usado para encerrar a sessão no navegador. */
    public static ResponseCookie expirado(boolean seguro) {
        return base("", seguro).maxAge(Duration.ZERO).build();
    }

    private static ResponseCookie.ResponseCookieBuilder base(String valor, boolean seguro) {
        return ResponseCookie.from(NOME, valor)
                .httpOnly(true)
                .secure(seguro)
                .sameSite("Strict")
                .path(CAMINHO);
    }
}
