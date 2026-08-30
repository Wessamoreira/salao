package br.com.salao.iam.internal.web;

import br.com.salao.iam.api.Capacidades;
import br.com.salao.iam.internal.application.ConsultarCapacidadesUseCase;
import br.com.salao.iam.internal.application.TrocarSenhaUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import br.com.salao.shared.tenant.TenantContext;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-IAM-006 — o contrato que o front consome ao entrar.
 *
 * <p>Alcançável mesmo com MFA pendente: é por aqui que o front descobre que precisa forçar a
 * inscrição. Bloqueá-lo deixaria a tela sem informação nenhuma para explicar o bloqueio.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final ConsultarCapacidadesUseCase capacidades;
    private final TrocarSenhaUseCase trocarSenha;

    public MeController(ConsultarCapacidadesUseCase capacidades, TrocarSenhaUseCase trocarSenha) {
        this.capacidades = capacidades;
        this.trocarSenha = trocarSenha;
    }

    /** O id vem do token, nunca do corpo — senão isto seria "trocar a senha de qualquer um". */
    public record TrocarSenhaRequest(@NotBlank String senhaAtual,
                                     @NotBlank @Size(max = 200) String senhaNova) {
    }

    @PostMapping("/senha")
    public ResponseEntity<Void> trocarSenha(@AuthenticationPrincipal Jwt jwt,
                                            @Valid @RequestBody TrocarSenhaRequest requisicao) {
        trocarSenha.executar(UUID.fromString(jwt.getSubject()), requisicao.senhaAtual(),
                requisicao.senhaNova());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/capabilities")
    public Capacidades capabilities(@AuthenticationPrincipal Jwt jwt) {
        return capacidades.executar(UUID.fromString(jwt.getSubject()),
                TenantContext.obrigatorio());
    }
}
