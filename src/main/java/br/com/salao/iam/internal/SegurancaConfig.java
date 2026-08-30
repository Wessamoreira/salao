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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.web.SecurityFilterChain;

/** RT-IAM-002 — autenticação e autorização. */
@Configuration(proxyBeanMethods = false)
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

    @Bean
    public JwtDecoder jwtDecoder(@Value("${app.jwt.segredo}") String segredo) {
        return NimbusJwtDecoder.withSecretKey(chave(segredo))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
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

    @Bean
    public SecurityFilterChain cadeiaDaApi(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        // Tudo o mais fechado por padrão: endpoint novo nasce protegido, e é
                        // preciso um ato deliberado para abri-lo. O contrário — abrir por padrão
                        // e lembrar de fechar — falha na primeira distração.
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> { }))
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
