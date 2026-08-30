package br.com.salao.iam.internal.web;

import br.com.salao.iam.internal.application.AutenticarCommand;
import br.com.salao.iam.internal.application.AutenticarUseCase;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-IAM-002 — o único endpoint aberto do sistema.
 *
 * <p>Transporte apenas: não decide nada. A autorização e a política de bloqueio vivem no caso de
 * uso, porque o mesmo login precisa valer para o bot do WhatsApp na Fase 4, que não passa por
 * controller nenhum.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AutenticacaoController {

    private final AutenticarUseCase autenticar;

    public AutenticacaoController(AutenticarUseCase autenticar) {
        this.autenticar = autenticar;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest requisicao) {
        var token = autenticar.executar(
                new AutenticarCommand(requisicao.email(), requisicao.senha()));
        return LoginResponse.de(token);
    }
}
