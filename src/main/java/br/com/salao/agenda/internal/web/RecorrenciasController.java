package br.com.salao.agenda.internal.web;

import br.com.salao.agenda.api.EsperaNaFila;
import br.com.salao.agenda.api.Frequencia;
import br.com.salao.agenda.api.SerieCriada;
import br.com.salao.agenda.internal.application.CriarRecorrenciaUseCase;
import br.com.salao.agenda.internal.application.EncerrarRecorrenciaUseCase;
import br.com.salao.agenda.internal.application.EnfileirarDiaDaSerieUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-AGD-009 — a cliente que vem toda semana.
 *
 * <p>A resposta traz o que <strong>não</strong> coube junto com o que coube: recusar a série
 * inteira porque a terceira quinta é feriado seria absurdo, e esconder as recusadas faria a
 * recepção descobrir uma a uma quando as clientes ligassem.
 */
@RestController
@RequestMapping("/api/v1/recorrencias")
public class RecorrenciasController {

    private final CriarRecorrenciaUseCase criar;
    private final EncerrarRecorrenciaUseCase encerrar;
    private final EnfileirarDiaDaSerieUseCase enfileirar;

    public RecorrenciasController(CriarRecorrenciaUseCase criar,
                                  EncerrarRecorrenciaUseCase encerrar,
                                  EnfileirarDiaDaSerieUseCase enfileirar) {
        this.criar = criar;
        this.encerrar = encerrar;
        this.enfileirar = enfileirar;
    }

    public record CriarSerieRequest(
            @NotNull UUID clienteId,
            @NotNull UUID profissionalId,
            @NotNull UUID servicoId,
            @NotNull Instant primeiroInicio,
            @NotNull Frequencia frequencia,
            @NotNull Integer quantidade,
            @Size(max = 1000) String observacao) {
    }

    @PostMapping
    public ResponseEntity<SerieCriada> criar(@Valid @RequestBody CriarSerieRequest req) {
        SerieCriada serie = criar.executar(req.clienteId(), req.profissionalId(), req.servicoId(),
                req.primeiroInicio(), req.frequencia(), req.quantidade(), req.observacao());
        return ResponseEntity
                .created(URI.create("/api/v1/recorrencias/" + serie.recorrencia().id()))
                .body(serie);
    }

    /** Encerrar cancela as ocorrências futuras; o passado fica como está. */
    public record EncerrarRequest(@NotBlank @Size(max = 500) String motivo) {
    }

    @PatchMapping("/{id}/encerramento")
    public Map<String, Integer> encerrar(@PathVariable UUID id,
                                         @Valid @RequestBody EncerrarRequest req) {
        return Map.of("canceladas", encerrar.executar(id, req.motivo()));
    }

    /** RT-AGD-010 — um dia que não coube vai para a fila; um por vez, por decisão do dono. */
    public record DiaNaFilaRequest(@NotNull LocalDate dia) {
    }

    @PostMapping("/{id}/fila-de-espera")
    public ResponseEntity<EsperaNaFila> porDiaNaFila(@PathVariable UUID id,
                                                     @Valid @RequestBody DiaNaFilaRequest req) {
        EsperaNaFila espera = enfileirar.executar(id, req.dia());
        return ResponseEntity.created(URI.create("/api/v1/fila-de-espera/" + espera.id()))
                .body(espera);
    }
}
