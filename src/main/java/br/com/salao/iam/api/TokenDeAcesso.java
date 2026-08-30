package br.com.salao.iam.api;

import java.time.Instant;
import java.util.UUID;

/**
 * RT-IAM-002 — o resultado de um login bem-sucedido.
 *
 * <p>Não traz refresh token: rotação com detecção de reuso é RT-IAM-003, e entregar um refresh
 * mal projetado agora seria pior do que não ter — ele é o token de vida longa.
 */
public record TokenDeAcesso(
        String token,
        Instant expiraEm,
        UUID usuarioId,
        UUID estabelecimentoId,
        Perfil perfil) {
}
