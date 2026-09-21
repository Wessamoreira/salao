package br.com.salao.estoque.internal.web;

import br.com.salao.estoque.internal.application.ConsultarDecisoesDePrecoUseCase;
import br.com.salao.estoque.internal.application.ConsultarSugestoesDePrecoUseCase;
import br.com.salao.estoque.internal.application.ConsultarSugestoesDePrecoUseCase.Painel;
import br.com.salao.estoque.internal.application.DefinirPoliticaDePrecoUseCase;
import br.com.salao.estoque.internal.application.DecidirSugestaoDePrecoUseCase;
import br.com.salao.estoque.internal.application.GerarSugestoesDePrecoUseCase;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.paginacao.Pagina;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final DecidirSugestaoDePrecoUseCase decidir;
    private final ConsultarDecisoesDePrecoUseCase trilha;

    public SugestoesDePrecoController(DefinirPoliticaDePrecoUseCase definir,
                                      GerarSugestoesDePrecoUseCase gerar,
                                      ConsultarSugestoesDePrecoUseCase consultar,
                                      DecidirSugestaoDePrecoUseCase decidir,
                                      ConsultarDecisoesDePrecoUseCase trilha) {
        this.definir = definir;
        this.gerar = gerar;
        this.consultar = consultar;
        this.decidir = decidir;
        this.trilha = trilha;
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

    // ------------------------------------------------------------------ RT-EST-011 — decidir

    /** @param preco opcional: sem ele, aplica o sugerido */
    public record AprovacaoRequest(BigDecimal preco) {
    }

    public record RecusaRequest(@NotBlank @Size(max = 500) String motivo) {
    }

    /**
     * Aplica o preço — o clique humano que fecha a Fase 6.
     *
     * <p>{@code POST} num sub-recurso, e não {@code PUT} no produto: o que acontece aqui é uma
     * decisão sobre uma sugestão, e ela é registrada. Mudar preço direto continua sendo na tela de
     * produto.
     */
    @PostMapping("/sugestoes-de-preco/{id}/aprovacao")
    public Painel aprovar(@PathVariable UUID id, @RequestBody(required = false) AprovacaoRequest req) {
        decidir.aprovar(id, req == null || req.preco() == null ? null : new Money(req.preco()));
        return consultar.executar();
    }

    @PostMapping("/sugestoes-de-preco/{id}/recusa")
    public Painel recusar(@PathVariable UUID id, @Valid @RequestBody RecusaRequest req) {
        decidir.recusar(id, req.motivo());
        return consultar.executar();
    }

    @GetMapping("/decisoes-de-preco")
    public Pagina<ConsultarDecisoesDePrecoUseCase.Item> decisoes(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limite) {
        return trilha.listar(cursor, limite);
    }
}
