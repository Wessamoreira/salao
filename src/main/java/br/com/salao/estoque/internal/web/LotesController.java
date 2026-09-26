package br.com.salao.estoque.internal.web;

import br.com.salao.estoque.internal.application.DevolverAoFornecedorUseCase;
import br.com.salao.estoque.internal.application.RegistrarAjusteUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-EST-013 — o que se faz num lote nomeado.
 *
 * <h2>Por que ajuste e devolução são do lote, e perda é do produto</h2>
 *
 * <p>Não é detalhe de API: é o que a pessoa sabe. Quem viu o xampu vazar no chão sabe o produto e
 * a quantidade, não o lote — e o FEFO escolhe (daí {@code POST /produtos/{id}/perdas}). Quem tem o
 * frasco vencido na mão, com a etiqueta, sabe o lote. Obrigar a escolher lote na perda faria
 * alguém escolher o errado só para poder continuar, e o número certo entraria no lugar errado.
 *
 * <p>Recurso próprio, e não {@code /produtos/{id}/lotes/{loteId}/…}: o lote tem id próprio e vive
 * sob RLS, então o produto no caminho seria enfeite que precisa ser conferido para não mentir.
 */
@RestController
@RequestMapping("/api/v1/lotes")
public class LotesController {

    private final RegistrarAjusteUseCase ajustar;
    private final DevolverAoFornecedorUseCase devolver;

    public LotesController(RegistrarAjusteUseCase ajustar, DevolverAoFornecedorUseCase devolver) {
        this.ajustar = ajustar;
        this.devolver = devolver;
    }

    /**
     * @param quantidade no ajuste é <strong>com sinal</strong>: negativa sumiu, positiva apareceu
     * @param motivo obrigatório (RN-EST-014), e conferido também pelo banco
     */
    public record AjusteRequest(
            @NotNull BigDecimal quantidade,
            @Size(max = 500) String motivo) {
    }

    /** @param quantidade sempre positiva: o sinal é do caso de uso, que sabe que devolver é saída */
    public record DevolucaoRequest(
            @NotNull @Positive BigDecimal quantidade,
            @Size(max = 500) String motivo) {
    }

    @PostMapping("/{loteId}/ajustes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void ajustar(@PathVariable UUID loteId, @Valid @RequestBody AjusteRequest req) {
        ajustar.executar(loteId, req.quantidade(), req.motivo());
    }

    @PostMapping("/{loteId}/devolucoes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void devolver(@PathVariable UUID loteId, @Valid @RequestBody DevolucaoRequest req) {
        devolver.executar(loteId, req.quantidade(), req.motivo());
    }
}
