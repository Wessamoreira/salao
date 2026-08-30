package br.com.salao.iam.internal.domain;

import java.time.Instant;
import java.util.UUID;

/** RT-IAM-005 — o segundo fator de um usuário, já decifrado. */
public record MfaCredencial(UUID id, String segredoBase32, Instant confirmadoEm,
                            Long ultimoContador) {

    /** Segredo gerado mas nunca confirmado não vale como segundo fator. */
    public boolean confirmada() {
        return confirmadoEm != null;
    }
}
