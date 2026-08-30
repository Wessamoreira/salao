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

    /**
     * Marca um token que <strong>não</strong> é de acesso.
     *
     * <p>Sem essa distinção, o desafio de segundo fator seria um JWT válido como qualquer outro —
     * e apresentá-lo no {@code Authorization} daria acesso à API sem nunca ter passado pelo MFA.
     * {@code SegurancaConfig} recusa, no recurso protegido, todo token que traga esta claim.
     */
    public static final String CLAIM_ESCOPO = "escopo";

    public static final String ESCOPO_SEGUNDO_FATOR = "mfa-pendente";

    private final JwtEncoder codificador;
    private final Relogio relogio;
    private final Duration validade;
    private final String emissor;

    private static final Duration VALIDADE_DO_DESAFIO = Duration.ofMinutes(5);

    public EmissorDeTokenJwt(JwtEncoder codificador, Relogio relogio, Duration validade,
                             String emissor) {
        this.codificador = codificador;
        this.relogio = relogio;
        this.validade = validade;
        this.emissor = emissor;
    }

    /**
     * Desafio de segundo fator: atesta que a senha foi conferida, e nada além disso.
     *
     * <p>Vida curta porque é uma credencial parcial em trânsito. Cinco minutos cobrem procurar o
     * celular e abrir o autenticador; mais que isso só amplia a janela em que a senha, já
     * conferida, vale alguma coisa sozinha.
     */
    public TokenDeAcesso emitirDesafioDeSegundoFator(UUID usuarioId, UUID estabelecimentoId) {
        Instant agora = relogio.agora();
        Instant expiraEm = agora.plus(VALIDADE_DO_DESAFIO);

        var claims = JwtClaimsSet.builder()
                .issuer(emissor)
                .issuedAt(agora)
                .expiresAt(expiraEm)
                .subject(usuarioId.toString())
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_ESTABELECIMENTO, estabelecimentoId.toString())
                .claim(CLAIM_ESCOPO, ESCOPO_SEGUNDO_FATOR)
                .build();

        var cabecalho = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = codificador.encode(JwtEncoderParameters.from(cabecalho, claims))
                .getTokenValue();
        return new TokenDeAcesso(token, expiraEm, usuarioId, estabelecimentoId, null);
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
