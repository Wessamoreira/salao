package br.com.salao.financeiro.internal.web;

import br.com.salao.financeiro.api.Lancamento;
import br.com.salao.financeiro.api.TipoDeLancamento;
import br.com.salao.financeiro.internal.application.EstornarLancamentoUseCase;
import br.com.salao.financeiro.internal.application.RegistrarAjusteNoRazaoUseCase;
import br.com.salao.shared.dinheiro.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-FIN-007 — a porta do estorno de lançamento.
 *
 * <p>Não há {@code GET} de lançamento solto: quem lê o razão lê pelo extrato (RT-FIN-004), que já
 * traz contexto e sinal invertido.
 *
 * <p>O {@code POST} chegou em RT-FIN-013: é o caminho que RN-FIN-023 já oferecia por escrito para
 * quem quer o valor de volta depois de um estorno, e que não existia.
 *
 * <p>{@code POST} num sub-recurso, e não {@code DELETE}: o lançamento não é apagado. A role não
 * tem {@code delete} nesta tabela (RN-FIN-008), e o verbo precisa prometer o que acontece.
 */
@RestController
@RequestMapping("/api/v1/lancamentos")
public class LancamentosController {

    private final EstornarLancamentoUseCase estornar;
    private final RegistrarAjusteNoRazaoUseCase registrar;

    public LancamentosController(EstornarLancamentoUseCase estornar,
                                 RegistrarAjusteNoRazaoUseCase registrar) {
        this.estornar = estornar;
        this.registrar = registrar;
    }

    /** O motivo é o que separa correção de desfazimento silencioso. */
    public record MotivoRequest(@NotBlank @Size(max = 500) String motivo) {
    }

    /**
     * RN-FIN-041 — um ajuste no razão.
     *
     * @param valor no <strong>sinal de quem recebe</strong>: positivo aumenta o que a pessoa tem
     *              a receber. O razão inverte uma vez, no caso de uso
     */
    public record AjusteRequest(
            @NotNull UUID profissionalId,
            @NotNull BigDecimal valor,
            @NotBlank @Size(max = 500) String motivo) {
    }

    /** A linha como a tela a lê: dinheiro em número, e o sinal de volta ao de quem recebe. */
    public record LancamentoResponse(
            UUID id,
            LocalDate dataCompetencia,
            TipoDeLancamento tipo,
            BigDecimal valorParaQuemRecebe,
            String descricao) {
    }

    /**
     * Escreve um ajuste manual.
     *
     * <p>Sem {@code Idempotency-Key}, ao contrário do vale: o ajuste não chega de bot nem de
     * webhook, e a chave existe para o reenvio automático (inviolável nº 10). Um clique repetido
     * escreve duas linhas — e, num livro append-only, duas linhas com o mesmo motivo é a verdade
     * do que aconteceu, corrigível por estorno.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LancamentoResponse registrar(@Valid @RequestBody AjusteRequest req) {
        Lancamento lancamento = registrar.executar(req.profissionalId(),
                new Money(req.valor()), req.motivo());
        return new LancamentoResponse(lancamento.id(), lancamento.dataCompetencia(),
                lancamento.tipo(), lancamento.valor().negado().valor(), lancamento.descricao());
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
