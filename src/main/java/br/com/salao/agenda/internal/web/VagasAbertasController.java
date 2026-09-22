package br.com.salao.agenda.internal.web;

import br.com.salao.agenda.internal.application.ConsultarVagasAbertasUseCase;
import br.com.salao.agenda.internal.application.RegistrarChamadaDaFilaUseCase;
import br.com.salao.agenda.internal.application.VagaComCandidatas;
import br.com.salao.agenda.internal.domain.ChamadaDaFila;
import br.com.salao.agenda.internal.domain.ResultadoDaChamada;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-AGD-010 — as vagas que abriram, quem cabe nelas, e o que cada uma respondeu.
 *
 * <p>A recepção chama; o sistema organiza. Não há mensagem automática: sem provedor de WhatsApp, a
 * oferta ficaria {@code SEM_PROVEDOR} sem chegar a ninguém (decisão do dono, 22/09/2026).
 */
@RestController
@RequestMapping("/api/v1/vagas-abertas")
public class VagasAbertasController {

    private final ConsultarVagasAbertasUseCase consultar;
    private final RegistrarChamadaDaFilaUseCase registrar;

    public VagasAbertasController(ConsultarVagasAbertasUseCase consultar,
                                  RegistrarChamadaDaFilaUseCase registrar) {
        this.consultar = consultar;
        this.registrar = registrar;
    }

    /** Só as vagas com alguém que cabe, da mais próxima para a mais distante. */
    @GetMapping
    public List<VagaComCandidatas> abertas() {
        return consultar.executar();
    }

    /**
     * @param inicio       o horário escolhido — obrigatório quando ela aceitou
     * @param motivoDoPulo obrigatório quando há alguém antes na ordem ainda não chamada nesta vaga
     */
    public record RegistrarChamadaRequest(
            @NotNull UUID esperaId,
            @NotNull ResultadoDaChamada resultado,
            Instant inicio,
            @Size(max = 300) String motivoDoPulo) {
    }

    public record ChamadaResposta(
            UUID id,
            UUID vagaId,
            UUID esperaId,
            ResultadoDaChamada resultado,
            UUID agendamentoId,
            String motivoDoPulo,
            Instant registradaEm) {

        static ChamadaResposta de(ChamadaDaFila chamada) {
            return new ChamadaResposta(chamada.id(), chamada.vagaId(), chamada.esperaId(),
                    chamada.resultado(), chamada.agendamentoId(), chamada.motivoDoPulo(),
                    chamada.registradaEm());
        }
    }

    @PostMapping("/{id}/chamadas")
    public ResponseEntity<ChamadaResposta> registrar(@PathVariable UUID id,
                                                     @Valid @RequestBody RegistrarChamadaRequest req) {
        ChamadaDaFila chamada = registrar.executar(id, req.esperaId(), req.resultado(),
                req.inicio(), req.motivoDoPulo());
        return ResponseEntity.created(URI.create("/api/v1/vagas-abertas/" + id + "/chamadas/"
                        + chamada.id()))
                .body(ChamadaResposta.de(chamada));
    }
}
