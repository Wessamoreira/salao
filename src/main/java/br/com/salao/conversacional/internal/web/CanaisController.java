package br.com.salao.conversacional.internal.web;

import br.com.salao.conversacional.internal.application.ConsultarCanalUseCase;
import br.com.salao.conversacional.internal.application.RegistrarCanalDoWhatsAppUseCase;
import br.com.salao.conversacional.api.CanalDoWhatsApp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-CNV-001 — o cadastro do canal, do lado do salão.
 *
 * <p>Fica em {@code /api/v1}, autenticado e com {@code config:manage}: é operação de gente, ao
 * contrário do webhook. As duas metades da rotina têm posturas de segurança opostas, e é por isso
 * que são dois controllers.
 */
@RestController
@RequestMapping("/api/v1/canais/whatsapp")
public class CanaisController {

    private final RegistrarCanalDoWhatsAppUseCase registrar;
    private final ConsultarCanalUseCase consultar;

    public CanaisController(RegistrarCanalDoWhatsAppUseCase registrar,
                            ConsultarCanalUseCase consultar) {
        this.registrar = registrar;
        this.consultar = consultar;
    }

    @GetMapping
    public CanalDoWhatsApp consultar() {
        return consultar.executar();
    }

    /** Idempotente por natureza: chamar de novo com os mesmos dados não muda nada. */
    @PutMapping
    public CanalDoWhatsApp registrar(@Valid @RequestBody CanalRequest req) {
        registrar.executar(req.phoneNumberId(), req.numeroExibicao(),
                req.ativo() == null || req.ativo());
        return consultar.executar();
    }

    /** @param ativo nulo vira {@code true}: quem cadastra um canal quer que ele receba */
    public record CanalRequest(
            @NotBlank @Size(max = 64) String phoneNumberId,
            @NotBlank @Size(max = 32) String numeroExibicao,
            Boolean ativo) {
    }
}
