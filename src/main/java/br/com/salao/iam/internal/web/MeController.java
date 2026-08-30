package br.com.salao.iam.internal.web;

import br.com.salao.iam.api.Capacidades;
import br.com.salao.iam.internal.application.ConsultarCapacidadesUseCase;
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

    public MeController(ConsultarCapacidadesUseCase capacidades) {
        this.capacidades = capacidades;
    }

    @GetMapping("/capabilities")
    public Capacidades capabilities(@AuthenticationPrincipal Jwt jwt) {
        return capacidades.executar(UUID.fromString(jwt.getSubject()),
                TenantContext.obrigatorio());
    }
}
