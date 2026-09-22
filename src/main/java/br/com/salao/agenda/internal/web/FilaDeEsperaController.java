package br.com.salao.agenda.internal.web;

import br.com.salao.agenda.api.EsperaNaFila;
import br.com.salao.agenda.internal.application.EsperaComNomes;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-AGD-010 — a fila de espera.
 *
 * <p>Quem cabe numa vaga que abriu é de {@code /api/v1/vagas-abertas}: a pergunta não é mais "quem
 * espera este serviço", e sim "quem a disponibilidade aceita neste tempo" (RN-AGD-025).
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

    /**
     * Uma espera como a tela a escreve.
     *
     * @param profissional nulo quando ela aceita qualquer uma
     * @param recorrenciaId a série de onde veio, quando é um dia dela que não coube
     */
    public record EsperaResposta(
            UUID id,
            UUID clienteId,
            String cliente,
            UUID servicoId,
            String servico,
            UUID profissionalId,
            String profissional,
            LocalDate aceitaDe,
            LocalDate aceitaAte,
            LocalTime horaDe,
            LocalTime horaAte,
            String observacao,
            Instant naFilaDesde,
            UUID recorrenciaId) {

        static EsperaResposta de(EsperaComNomes linha) {
            EsperaNaFila espera = linha.espera();
            return new EsperaResposta(espera.id(), espera.clienteId(), linha.cliente(),
                    espera.servicoId(), linha.servico(), espera.profissionalId(),
                    linha.profissional(), espera.aceitaDe(), espera.aceitaAte(), espera.horaDe(),
                    espera.horaAte(), espera.observacao(), espera.criadoEm(),
                    espera.recorrenciaId());
        }
    }

    /** Quem está esperando, do mais antigo para o mais novo. */
    @GetMapping
    public List<EsperaResposta> naFila() {
        return fila.naFila().stream().map(EsperaResposta::de).toList();
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
