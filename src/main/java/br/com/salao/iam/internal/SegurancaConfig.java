package br.com.salao.iam.internal;

import br.com.salao.iam.internal.infra.ResolvedorDeTenantPorJwt;
import br.com.salao.shared.tenant.ResolvedorDeTenant;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.internal.domain.MapaDePermissoes;
import br.com.salao.iam.internal.infra.ConversorDePermissoes;
import br.com.salao.iam.internal.infra.EmissorDeTokenJwt;
import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.web.SecurityFilterChain;

/** RT-IAM-002 — autenticação e autorização. */
@Configuration(proxyBeanMethods = false)
// Liga @PreAuthorize nos casos de uso — é onde a autorização acontece, nunca no controller.
@org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
public class SegurancaConfig {

    /**
     * Argon2id, não BCrypt.
     *
     * <p>BCrypt resiste a GPU mas não a hardware com muita memória; Argon2id foi desenhado para
     * ser caro em memória, que é o recurso difícil de paralelizar barato. Para senha nova não há
     * motivo para escolher o mais antigo.
     *
     * <p><strong>Depende do BouncyCastle no classpath</strong> — sem ele, a falha só aparece em
     * runtime, na primeira tentativa de hash. A dependência está declarada no {@code pom.xml} com
     * esse aviso.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    public JwtEncoder jwtEncoder(@Value("${app.jwt.segredo}") String segredo) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(chave(segredo)));
    }

    /**
     * Decodificador usado para <em>emitir e conferir</em> qualquer JWT nosso, inclusive o desafio
     * de segundo fator. Quem restringe o desafio a seu lugar é o {@link #validadorDeEscopo}, na
     * cadeia do recurso protegido.
     */
    @Bean
    @org.springframework.context.annotation.Primary
    public JwtDecoder jwtDecoder(@Value("${app.jwt.segredo}") String segredo) {
        return NimbusJwtDecoder.withSecretKey(chave(segredo))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * Recusa, no {@code Authorization}, qualquer token que carregue {@code escopo}.
     *
     * <p>O desafio de segundo fator é um JWT assinado por nós e, sem esta checagem, seria aceito
     * como credencial em toda a API — dando acesso a quem só passou pela senha e nunca apresentou
     * o segundo fator. É a falha que transformaria o MFA em teatro.
     */
    @Bean
    public JwtDecoder jwtDecoderDoRecurso(@Value("${app.jwt.segredo}") String segredo) {
        var decodificador = NimbusJwtDecoder.withSecretKey(chave(segredo))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decodificador.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(), validadorDeEscopo()));
        return decodificador;
    }

    private OAuth2TokenValidator<Jwt> validadorDeEscopo() {
        return jwt -> jwt.getClaimAsString(EmissorDeTokenJwt.CLAIM_ESCOPO) == null
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "token_invalido",
                        "Token de escopo restrito não vale como credencial de acesso", null));
    }

    private SecretKeySpec chave(String segredo) {
        if (segredo == null || segredo.getBytes().length < 32) {
            // HS256 com chave curta é trivialmente quebrável por força bruta. Falhar na subida é
            // melhor que emitir tokens frágeis em silêncio.
            throw new IllegalStateException(
                    "app.jwt.segredo precisa de ao menos 32 bytes");
        }
        return new SecretKeySpec(segredo.getBytes(), "HmacSHA256");
    }

    @Bean
    public ResolvedorDeTenant resolvedorDeTenantPorJwt() {
        return new ResolvedorDeTenantPorJwt();
    }

    /**
     * Actuator liberado — ele já vive em outra porta (RT-INF-008), fora da rota pública.
     *
     * <p>Isto não é autenticação: qualquer um dentro da rede alcança a porta de gerenciamento.
     * Continua sendo pendência, e o motivo de não resolver aqui é que health e métrica precisam
     * ser legíveis por sonda de contêiner e por scraper, que não carregam token.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain cadeiaDoActuator(HttpSecurity http) throws Exception {
        return http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .build();
    }

    @Value("${app.jwt.segredo}")
    private String segredoDoJwt;

    private JwtAuthenticationConverter conversorDeAutenticacao() {
        var conversor = new JwtAuthenticationConverter();
        conversor.setJwtGrantedAuthoritiesConverter(new ConversorDePermissoes());
        return conversor;
    }

    /**
     * RN-IAM-014 — imposição do segundo fator <strong>no backend</strong>.
     *
     * <p>Sem isto, "MFA obrigatório para ADMIN" seria só um campo em {@code /me/capabilities} para
     * o front respeitar — e o próprio projeto diz que esconder botão é UX, não segurança. Quem
     * chamasse a API diretamente entraria sem segundo fator nenhum.
     *
     * <p>A verificação é por permissão, e não por lista de perfis: continua valendo quando o mapa
     * mudar.
     */
    private AuthorizationDecision segundoFatorEmDia(
            Supplier<? extends Authentication> autenticacao,
            RequestAuthorizationContext contexto) {
        var atual = autenticacao.get();
        if (atual == null || !atual.isAuthenticated()
                || !(atual.getPrincipal() instanceof Jwt jwt)) {
            return new AuthorizationDecision(false);
        }
        String perfil = jwt.getClaimAsString(EmissorDeTokenJwt.CLAIM_PERFIL);
        if (perfil == null) {
            return new AuthorizationDecision(false);
        }
        boolean exige;
        try {
            exige = MapaDePermissoes.exigeMfa(Perfil.valueOf(perfil));
        } catch (IllegalArgumentException e) {
            return new AuthorizationDecision(false);
        }
        boolean tem = Boolean.TRUE.equals(jwt.getClaim(EmissorDeTokenJwt.CLAIM_MFA));
        return new AuthorizationDecision(!exige || tem);
    }

    @Bean
    public SecurityFilterChain cadeiaDaApi(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        // Aberto porque é usado exatamente quando o access token expirou.
                        // Quem autentica aqui é o cookie HttpOnly, não o Authorization.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh").permitAll()
                        // Sair não pode depender de um access token que talvez já tenha expirado.
                        // O que autentica é o cookie; e sem cookie válido o logout é inofensivo.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").permitAll()
                        // Quem chega aqui tem só o desafio, que sozinho não abre nada.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/mfa/verificar").permitAll()
                        // Alcançáveis mesmo com MFA pendente. Os dois primeiros são o caminho
                        // para sair do bloqueio: sem eles, quem precisa de segundo fator ficaria
                        // trancado sem ter como se inscrever, e sem informação para a tela
                        // explicar o motivo.
                        //
                        // logout-all está aqui por outro motivo: é ação de SEGURANÇA. Quem
                        // suspeita que perdeu o dispositivo precisa poder encerrar as sessões
                        // mesmo com MFA pendente — bloquear reduziria a segurança em nome de
                        // uma regra de segurança.
                        .requestMatchers("/api/v1/auth/mfa/**", "/api/v1/me/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout-all")
                        .authenticated()
                        // Tudo o mais fechado por padrão: endpoint novo nasce protegido, e é
                        // preciso um ato deliberado para abri-lo. O contrário — abrir por padrão
                        // e lembrar de fechar — falha na primeira distração.
                        .anyRequest().access(this::segundoFatorEmDia))
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt
                        .decoder(jwtDecoderDoRecurso(segredoDoJwt))
                        .jwtAuthenticationConverter(conversorDeAutenticacao())))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Sem sessão e sem cookie, não há o que um site terceiro possa forjar: o token vai
                // no cabeçalho Authorization, que ele não consegue definir. Isto muda em
                // RT-IAM-003, quando o refresh passar a viver num cookie — aí CSRF volta a valer.
                .csrf(csrf -> csrf.disable())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .build();
    }
}
