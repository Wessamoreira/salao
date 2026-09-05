package br.com.salao.agenda.internal.web;

import br.com.salao.agenda.api.EsperaNaFila;
import br.com.salao.agenda.internal.application.FilaDeEsperaUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-AGD-010 — a fila de espera.
 *
 * <p>{@code /candidatos} é a rota que a recepção usa logo depois de cancelar: <em>abriu esta vaga,
 * quem cabe?</em> Não há disparo automático — avisar depende de {@code notificacao}, e registrar
 * "oferta enviada" sem enviar nada seria gravar uma mentira.
 */
@RestController
@RequestMapping("/api/v1/fila-de-espera")
public class FilaDeEsperaController {

    private final FilaDeEsperaUseCase fila;

    public FilaDeEsperaController(FilaDeEsperaUseCase fila) {
        this.fila = fila;
    }

    /**
     * {@code profissionalId} nulo é "qualquer um" — e é o caso em que a fila mais funciona, porque
     * qualquer cancelamento serve.
     */
    public record EntrarNaFilaRequest(
            @NotNull UUID clienteId,
            @NotNull UUID servicoId,
            UUID profissionalId,
            @NotNull LocalDate aceitaDe,
            @NotNull LocalDate aceitaAte,
            LocalTime horaDe,
            LocalTime horaAte,
            @Size(max = 500) String observacao) {
    }

    @PostMapping
    public ResponseEntity<EsperaNaFila> entrar(@Valid @RequestBody EntrarNaFilaRequest req) {
        EsperaNaFila criada = fila.entrar(req.clienteId(), req.servicoId(), req.profissionalId(),
                req.aceitaDe(), req.aceitaAte(), req.horaDe(), req.horaAte(), req.observacao());
        return ResponseEntity.created(URI.create("/api/v1/fila-de-espera/" + criada.id()))
                .body(criada);
    }

    @GetMapping
    public List<EsperaNaFila> naFila() {
        return fila.naFila();
    }

    /** Abriu esta vaga: quem cabe? Em ordem de quem chegou primeiro. */
    @GetMapping("/candidatos")
    public List<EsperaNaFila> candidatos(
            @RequestParam UUID servico,
            @RequestParam(required = false) UUID profissional,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant inicio) {
        return fila.candidatosA(servico, profissional, inicio);
    }

    /** Conseguiu horário. Diferente de desistir, e a diferença importa no relatório. */
    @PatchMapping("/{id}/atendimento")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void marcarAtendida(@PathVariable UUID id) {
        fila.marcarAtendida(id);
    }

    /** Cansou de esperar. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void desistir(@PathVariable UUID id) {
        fila.registrarDesistencia(id);
    }
}
