package br.com.salao.financeiro.internal.web;

import br.com.salao.financeiro.internal.application.ConsultarTaxasDoAdquirenteUseCase;
import br.com.salao.financeiro.internal.application.ConsultarTaxasDoAdquirenteUseCase.Faixa;
import br.com.salao.financeiro.internal.application.DefinirTaxasDoAdquirenteUseCase;
import br.com.salao.financeiro.internal.domain.FaixaDeTaxa;
import br.com.salao.financeiro.internal.domain.TaxaContratada;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-FIN-012 — o que o salão negociou com a adquirente.
 *
 * <p>{@code PUT} e não {@code POST}: o cadastro é um documento só — as quatro faixas —, e enviá-lo de
 * novo produz o mesmo estado. É a mesma forma das regras de comissão (RT-FIN-002).
 */
@RestController
@RequestMapping("/api/v1/adquirente/taxas")
public class TaxasDoAdquirenteController {

    private final DefinirTaxasDoAdquirenteUseCase definir;
    private final ConsultarTaxasDoAdquirenteUseCase consultar;

    public TaxasDoAdquirenteController(DefinirTaxasDoAdquirenteUseCase definir,
                                       ConsultarTaxasDoAdquirenteUseCase consultar) {
        this.definir = definir;
        this.consultar = consultar;
    }

    /** @param percentual em forma percentual: {@code 3.49} é 3,49% */
    public record FaixaRequest(@NotNull FaixaDeTaxa faixa, @NotNull BigDecimal percentual,
                               @NotNull Integer diasParaReceber) {
    }

    public record TaxasRequest(@NotEmpty @Valid List<FaixaRequest> faixas) {
    }

    @GetMapping
    public List<Faixa> listar() {
        return consultar.executar();
    }

    @PutMapping
    public List<Faixa> definir(@Valid @RequestBody TaxasRequest req) {
        definir.executar(req.faixas().stream()
                .map(f -> new TaxaContratada(f.faixa(), f.percentual(), f.diasParaReceber()))
                .toList());
        return consultar.executar();
    }
}
