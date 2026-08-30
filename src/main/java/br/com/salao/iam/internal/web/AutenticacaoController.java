package br.com.salao.iam.internal.web;

import br.com.salao.iam.api.SessaoIniciada;
import br.com.salao.iam.internal.application.AutenticarCommand;
import br.com.salao.iam.internal.application.AutenticarUseCase;
import br.com.salao.iam.internal.application.RenovarAcessoUseCase;
import br.com.salao.shared.tempo.Relogio;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-IAM-002/003 — os dois endpoints abertos do sistema.
 *
 * <p>Transporte apenas: não decide nada. A política de bloqueio e a detecção de reuso vivem nos
 * casos de uso, porque o mesmo login precisa valer para o bot do WhatsApp na Fase 4, que não passa
 * por controller nenhum.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AutenticacaoController {

    private final AutenticarUseCase autenticar;
    private final RenovarAcessoUseCase renovar;
    private final Relogio relogio;
    private final boolean cookieSeguro;

    public AutenticacaoController(AutenticarUseCase autenticar, RenovarAcessoUseCase renovar,
                                  Relogio relogio,
                                  @Value("${app.auth.cookie-seguro:true}") boolean cookieSeguro) {
        this.autenticar = autenticar;
        this.renovar = renovar;
        this.relogio = relogio;
        this.cookieSeguro = cookieSeguro;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest requisicao) {
        var sessao = autenticar.executar(
                new AutenticarCommand(requisicao.email(), requisicao.senha()));
        return responder(sessao);
    }

    /**
     * Aberto de propósito: o refresh é usado justamente quando o access token expirou. Quem
     * autentica aqui é o cookie, não o cabeçalho {@code Authorization}.
     */
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> renovar(
            @CookieValue(name = CookieDeRefresh.NOME, required = false) String refresh,
            HttpServletRequest requisicao) {
        var sessao = renovar.executar(refresh, requisicao.getRemoteAddr(),
                requisicao.getHeader(HttpHeaders.USER_AGENT));
        return responder(sessao);
    }

    private ResponseEntity<LoginResponse> responder(SessaoIniciada sessao) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        CookieDeRefresh.de(sessao, cookieSeguro, relogio.agora()).toString())
                .body(LoginResponse.de(sessao.acesso()));
    }
}
