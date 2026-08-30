package br.com.salao.iam.internal.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * RT-IAM-002 — corpo do login.
 *
 * <p>{@code @Size} no máximo, não no mínimo: recusar senha curta <em>aqui</em> revelaria a regra
 * de tamanho a quem está adivinhando, e a senha já existente não muda por causa da validação. O
 * limite superior existe para não gastar Argon2 — que é caro de propósito — sobre um megabyte
 * enviado por engano ou por má-fé.
 */
public record LoginRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(max = 200) String senha) {
}
