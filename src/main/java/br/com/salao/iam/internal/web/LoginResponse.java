package br.com.salao.iam.internal.web;

import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.api.TokenDeAcesso;
import java.time.Instant;
import java.util.UUID;

/**
 * RT-IAM-002 — resposta do login.
 *
 * <p>DTO próprio do endpoint, e não o {@code TokenDeAcesso} do domínio: são contratos com ciclos
 * de vida diferentes, e expor o tipo interno faria qualquer campo novo vazar para a API sem
 * ninguém decidir isso.
 */
public record LoginResponse(
        String tokenDeAcesso,
        String tipo,
        Instant expiraEm,
        UUID usuarioId,
        UUID estabelecimentoId,
        Perfil perfil) {

    public static LoginResponse de(TokenDeAcesso token) {
        return new LoginResponse(token.token(), "Bearer", token.expiraEm(),
                token.usuarioId(), token.estabelecimentoId(), token.perfil());
    }
}
