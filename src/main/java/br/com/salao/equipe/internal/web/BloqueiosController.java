package br.com.salao.equipe.internal.web;

import br.com.salao.equipe.api.BloqueioDeJornada;
import br.com.salao.equipe.api.IntervaloDeTrabalho;
import br.com.salao.equipe.api.MotivoDoBloqueio;
import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.equipe.internal.application.RegistrarBloqueioUseCase;
import br.com.salao.equipe.internal.application.RemoverBloqueioUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-EQP-003 — feriado, férias, curso, atestado.
 *
 * <p>Rota própria e não sob {@code /profissionais/{id}}: o bloqueio pode ser do salão inteiro, e
 * pendurá-lo na rota de um profissional obrigaria a inventar um id falso para o feriado.
 */
/*
 * A rota era /api/v1/bloqueios até RT-AGD-007, quando a agenda ganhou o SEU bloqueio — o da grade,
 * que ocupa um horário e vale para recurso. "Bloqueio" sozinho passou a significar duas coisas, e
 * a rota curta ficaria com a menos procurada: quem abre a grade e arrasta espera /bloqueios.
 * Renomear as duas custou uma linha cada e nada mais, porque nenhum front consome nenhuma delas
 * ainda.
 */
@RestController
@RequestMapping("/api/v1/bloqueios-de-jornada")
public class BloqueiosController {

    private final RegistrarBloqueioUseCase registrar;
    private final RemoverBloqueioUseCase remover;
    private final ProfissionalApi equipe;

    public BloqueiosController(RegistrarBloqueioUseCase registrar, RemoverBloqueioUseCase remover,
                               ProfissionalApi equipe) {
        this.registrar = registrar;
        this.remover = remover;
        this.equipe = equipe;
    }

    /**
     * @param profissionalId ausente significa o salão inteiro — ver
     *                       {@link BloqueioDeJornada#profissionalId()}
     */
    public record BloqueioRequest(
            UUID profissionalId,
            @NotNull MotivoDoBloqueio motivo,
            @Size(max = 500) String observacao,
            @NotNull LocalDate dataInicio,
            @NotNull LocalDate dataFim,
            LocalTime horaInicio,
            LocalTime horaFim) {
    }

    @GetMapping
    public List<BloqueioDeJornada> listar(
            @RequestParam(required = false) UUID profissionalId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim) {
        return equipe.bloqueiosEntre(profissionalId, inicio, fim);
    }

    @PostMapping
    public ResponseEntity<BloqueioDeJornada> registrar(@Valid @RequestBody BloqueioRequest req) {
        BloqueioDeJornada criado = registrar.executar(req.profissionalId(), req.motivo(),
                req.observacao(), req.dataInicio(), req.dataFim(), req.horaInicio(),
                req.horaFim());
        return ResponseEntity.created(URI.create("/api/v1/bloqueios/" + criado.id())).body(criado);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remover(@PathVariable UUID id) {
        remover.executar(id);
    }

    /**
     * O que sobra da jornada num dia concreto — jornada menos bloqueios.
     *
     * <p>É esta a resposta que a agenda usa, e não a jornada crua: a jornada diz "terça das 9h às
     * 19h", e no dia 14 a pessoa está em curso das 14h às 16h.
     */
    @GetMapping("/disponibilidade")
    public List<IntervaloDeTrabalho> disponibilidade(
            @RequestParam UUID profissionalId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data) {
        return equipe.intervalosEm(profissionalId, data);
    }
}
