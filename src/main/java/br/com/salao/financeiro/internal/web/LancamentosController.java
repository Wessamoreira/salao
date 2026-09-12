package br.com.salao.financeiro.internal.web;

import br.com.salao.financeiro.internal.application.EstornarLancamentoUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-FIN-007 — a porta do estorno de lançamento.
 *
 * <p>Só o estorno. Não há {@code GET} de lançamento solto nem {@code POST} de lançamento manual:
 * quem lê o razão lê pelo extrato (RT-FIN-004), que já traz contexto e sinal invertido, e escrever
 * no razão à mão é ato que ainda não tem rotina.
 *
 * <p>{@code POST} num sub-recurso, e não {@code DELETE}: o lançamento não é apagado. A role não
 * tem {@code delete} nesta tabela (RN-FIN-008), e o verbo precisa prometer o que acontece.
 */
@RestController
@RequestMapping("/api/v1/lancamentos")
public class LancamentosController {

    private final EstornarLancamentoUseCase estornar;

    public LancamentosController(EstornarLancamentoUseCase estornar) {
        this.estornar = estornar;
    }

    /** O motivo é o que separa correção de desfazimento silencioso. */
    public record MotivoRequest(@NotBlank @Size(max = 500) String motivo) {
    }

    /**
     * Anula um lançamento, na competência dele.
     *
     * <p>Repetir devolve {@code anuladas: 0} em vez de erro — quem repete quer o mesmo estado
     * final, e o razão já responde se ainda havia o que anular.
     */
    @PostMapping("/{id}/estorno")
    public Map<String, Integer> estornar(@PathVariable UUID id,
                                         @Valid @RequestBody MotivoRequest req) {
        return Map.of("anuladas", estornar.executar(id, req.motivo()));
    }
}
