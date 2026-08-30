package br.com.salao.iam.api;

import java.time.Instant;

/**
 * RT-IAM-003 — o par que uma sessão precisa.
 *
 * <p>O access token vai no corpo, para o front guardar em memória. O refresh **nunca** vai no
 * corpo: ele vai em cookie {@code HttpOnly}, onde JavaScript não alcança. Devolvê-lo no JSON
 * anularia a proteção — bastaria um XSS para levar o token de vida longa.
 */
public record SessaoIniciada(TokenDeAcesso acesso, String refresh, Instant refreshExpiraEm) {
}
