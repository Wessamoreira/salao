package br.com.salao.agenda.internal.web;

import br.com.salao.agenda.api.BloqueioDaGrade;
import br.com.salao.agenda.api.MotivoDoBloqueioDeAgenda;
import br.com.salao.agenda.internal.application.BloquearGradeUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
 * RT-AGD-007 — bloquear arrastando na grade.
 *
 * <p>O período vem do gesto: só o motivo precisa ser escolhido, e a descrição é opcional. Uma tela
 * com seis campos obrigatórios faria a recepção anotar no papel — que é o que este sistema existe
 * para evitar.
 *
 * <p>{@code /bloqueios-de-agenda} e não {@code /bloqueios}: {@code equipe} tem
 * {@code /bloqueios-de-jornada} (RT-EQP-003), que é outra coisa. As duas rotas ganharam o sufixo
 * quando esta nasceu — a curta ficaria com a menos procurada.
 */
@RestController
@RequestMapping("/api/v1/bloqueios-de-agenda")
public class BloqueiosDaGradeController {

    private final BloquearGradeUseCase bloquear;

    public BloqueiosDaGradeController(BloquearGradeUseCase bloquear) {
        this.bloquear = bloquear;
    }

    /**
     * Um dos dois alvos basta, e os dois juntos são legítimos: "a Ana e o lavatório 1 estão fora
     * das 14h às 15h" é uma frase que o salão diz.
     */
    public record BloqueioRequest(
            UUID profissionalId,
            UUID recursoId,
            @NotNull MotivoDoBloqueioDeAgenda motivo,
            @Size(max = 500) String descricao,
            @NotNull Instant inicio,
            @NotNull Instant fim) {
    }

    @PostMapping
    public ResponseEntity<BloqueioDaGrade> bloquear(@Valid @RequestBody BloqueioRequest req) {
        BloqueioDaGrade criado = bloquear.executar(req.profissionalId(), req.recursoId(),
                req.motivo(), req.descricao(), req.inicio(), req.fim());
        return ResponseEntity.created(URI.create("/api/v1/bloqueios-de-agenda/" + criado.id())).body(criado);
    }

    @GetMapping
    public List<BloqueioDaGrade> listar(@RequestParam Instant de, @RequestParam Instant ate) {
        return bloquear.listar(de, ate);
    }

    /** Aqui {@code DELETE} é certo, ao contrário do cancelamento: nada aponta para um bloqueio. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remover(@PathVariable UUID id) {
        bloquear.remover(id);
    }
}
