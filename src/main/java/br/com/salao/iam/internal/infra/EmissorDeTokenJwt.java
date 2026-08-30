package br.com.salao.iam.internal.infra;

import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.api.TokenDeAcesso;
import br.com.salao.shared.tempo.Relogio;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;

/**
 * RT-IAM-002 — emissão do access token.
 *
 * <p><strong>15 minutos, curto de propósito.</strong> O access token não é revogável: uma vez
 * emitido, vale até expirar. Se um usuário for desativado, ou o perfil dele mudar, o token antigo
 * continua aceito até o fim da validade — e é essa janela que a duração curta limita. Aumentar
 * para "reduzir logins" trocaria conforto por uma janela maior de acesso indevido; quem resolve o
 * conforto é o refresh (RT-IAM-003).
 *
 * <p>{@code estabelecimentoId} vai no token porque é dele que o {@code ResolvedorDeTenantPorJwt}
 * tira o tenant de cada requisição. Sem essa claim, cada chamada precisaria de uma consulta ao
 * banco só para descobrir de quem é o usuário.
 */
public class EmissorDeTokenJwt {

    public static final String CLAIM_ESTABELECIMENTO = "estabelecimentoId";
    public static final String CLAIM_PERFIL = "perfil";

    private final JwtEncoder codificador;
    private final Relogio relogio;
    private final Duration validade;
    private final String emissor;

    public EmissorDeTokenJwt(JwtEncoder codificador, Relogio relogio, Duration validade,
                             String emissor) {
        this.codificador = codificador;
        this.relogio = relogio;
        this.validade = validade;
        this.emissor = emissor;
    }

    public TokenDeAcesso emitir(UUID usuarioId, UUID estabelecimentoId, Perfil perfil) {
        Instant agora = relogio.agora();
        Instant expiraEm = agora.plus(validade);

        var claims = JwtClaimsSet.builder()
                .issuer(emissor)
                .issuedAt(agora)
                .expiresAt(expiraEm)
                .subject(usuarioId.toString())
                // jti identifica o token individualmente: é o que torna possível revogar um
                // token específico quando existir lista de revogação (RT-IAM-004).
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_ESTABELECIMENTO, estabelecimentoId.toString())
                .claim(CLAIM_PERFIL, perfil.name())
                .build();

        var cabecalho = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = codificador.encode(JwtEncoderParameters.from(cabecalho, claims))
                .getTokenValue();

        return new TokenDeAcesso(token, expiraEm, usuarioId, estabelecimentoId, perfil);
    }
}
