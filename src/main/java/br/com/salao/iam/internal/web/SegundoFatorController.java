package br.com.salao.iam.internal.web;

import br.com.salao.iam.internal.application.SegundoFatorUseCase;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** RT-IAM-005 — segundo fator. */
@RestController
@RequestMapping("/api/v1/auth/mfa")
public class SegundoFatorController {

    private final SegundoFatorUseCase segundoFator;
    private final Relogio relogio;
    private final boolean cookieSeguro;

    public SegundoFatorController(SegundoFatorUseCase segundoFator, Relogio relogio,
                                  @Value("${app.auth.cookie-seguro:true}") boolean cookieSeguro) {
        this.segundoFator = segundoFator;
        this.relogio = relogio;
        this.cookieSeguro = cookieSeguro;
    }

    public record CodigoRequest(@NotBlank @Size(max = 32) String codigo) {
    }

    public record VerificarRequest(@NotBlank String desafio,
                                   @NotBlank @Size(max = 32) String codigo) {
    }

    public record InscricaoResponse(String segredo, String uriOtpauth) {
    }

    public record CodigosDeRecuperacaoResponse(List<String> codigos, String aviso,
                                              LoginResponse sessao) {
    }

    @PostMapping("/inscrever")
    public InscricaoResponse inscrever(@AuthenticationPrincipal Jwt jwt) {
        var inscricao = segundoFator.inscrever(TenantContext.obrigatorio(), usuarioDe(jwt));
        return new InscricaoResponse(inscricao.segredo(), inscricao.uriOtpauth());
    }

    @PostMapping("/confirmar")
    public ResponseEntity<CodigosDeRecuperacaoResponse> confirmar(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CodigoRequest requisicao) {
        var confirmacao = segundoFator.confirmar(TenantContext.obrigatorio(), usuarioDe(jwt),
                requisicao.codigo());
        var corpo = new CodigosDeRecuperacaoResponse(confirmacao.codigos(),
                "Guarde estes códigos agora: eles não serão exibidos de novo. "
                        + "Cada um funciona uma única vez.",
                LoginResponse.de(confirmacao.sessao().acesso()));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, CookieDeRefresh
                        .de(confirmacao.sessao(), cookieSeguro, relogio.agora()).toString())
                .body(corpo);
    }

    /**
     * Aberto: quem chega aqui ainda não tem sessão — tem apenas o desafio, que só vale com o
     * código do autenticador.
     */
    @PostMapping("/verificar")
    public ResponseEntity<LoginResponse> verificar(@Valid @RequestBody VerificarRequest req) {
        var sessao = segundoFator.concluirLogin(req.desafio(), req.codigo());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        CookieDeRefresh.de(sessao, cookieSeguro, relogio.agora()).toString())
                .body(LoginResponse.de(sessao.acesso()));
    }

    @PostMapping("/desativar")
    public ResponseEntity<Void> desativar(@AuthenticationPrincipal Jwt jwt,
                                          @Valid @RequestBody CodigoRequest requisicao) {
        segundoFator.desativar(usuarioDe(jwt), requisicao.codigo());
        return ResponseEntity.noContent().build();
    }

    /**
     * O tenant vem do {@code TenantContext}, e não de uma claim lida aqui: o {@code TenantFilter}
     * já o extraiu do token, e reler a claim no controller duplicaria a regra em dois lugares que
     * poderiam divergir.
     */
    private UUID usuarioDe(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
