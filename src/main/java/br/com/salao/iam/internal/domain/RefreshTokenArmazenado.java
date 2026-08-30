package br.com.salao.iam.internal.domain;

import java.time.Instant;
import java.util.UUID;

/** RT-IAM-003 — o registro de um refresh token, como está no banco. */
public record RefreshTokenArmazenado(
        UUID id,
        UUID estabelecimentoId,
        UUID usuarioId,
        UUID familiaId,
        Instant expiraEm,
        Instant usadoEm,
        Instant revogadoEm) {

    public boolean revogado() {
        return revogadoEm != null;
    }

    public boolean jaUsado() {
        return usadoEm != null;
    }

    public boolean expirado(Instant agora) {
        return expiraEm.isBefore(agora);
    }
}
