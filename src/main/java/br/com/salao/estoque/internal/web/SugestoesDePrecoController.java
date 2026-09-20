package br.com.salao.estoque.internal.web;

import br.com.salao.estoque.internal.application.ConsultarSugestoesDePrecoUseCase;
import br.com.salao.estoque.internal.application.ConsultarSugestoesDePrecoUseCase.Painel;
import br.com.salao.estoque.internal.application.DefinirPoliticaDePrecoUseCase;
import br.com.salao.estoque.internal.application.GerarSugestoesDePrecoUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-EST-010 — a régua do salão e o que ela acha dos preços de hoje.
 *
 * <p>Gerar é {@code POST} e não {@code GET}: a rodada grava — substitui as sugestões abertas — e um
 * {@code GET} que escreve é o tipo de coisa que um recarregamento de página repete sem avisar.
 */
@RestController
@RequestMapping("/api/v1")
public class SugestoesDePrecoController {

    private final DefinirPoliticaDePrecoUseCase definir;
    private final GerarSugestoesDePrecoUseCase gerar;
    private final ConsultarSugestoesDePrecoUseCase consultar;

    public SugestoesDePrecoController(DefinirPoliticaDePrecoUseCase definir,
                                      GerarSugestoesDePrecoUseCase gerar,
                                      ConsultarSugestoesDePrecoUseCase consultar) {
        this.definir = definir;
        this.gerar = gerar;
        this.consultar = consultar;
    }

    /** @param margemMinima em forma percentual: {@code 30} é 30% */
    public record PoliticaRequest(@NotNull BigDecimal margemMinima) {
    }

    @PutMapping("/politica-de-preco")
    public Map<String, BigDecimal> definirPolitica(@Valid @RequestBody PoliticaRequest req) {
        definir.executar(req.margemMinima());
        return Map.of("margemMinima", req.margemMinima());
    }

    @GetMapping("/sugestoes-de-preco")
    public Painel listar() {
        return consultar.executar();
    }

    @PostMapping("/sugestoes-de-preco")
    public Painel gerar() {
        gerar.executar();
        return consultar.executar();
    }
}
