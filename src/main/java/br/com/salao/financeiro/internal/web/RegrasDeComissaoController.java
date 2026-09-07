package br.com.salao.financeiro.internal.web;

import br.com.salao.financeiro.api.ComissaoApi;
import br.com.salao.financeiro.api.ComissaoResolvida;
import br.com.salao.financeiro.api.EscopoDaRegra;
import br.com.salao.financeiro.api.RegraDeComissao;
import br.com.salao.financeiro.internal.application.DefinirRegraDeComissaoUseCase;
import br.com.salao.financeiro.internal.application.ListarRegrasDeComissaoUseCase;
import br.com.salao.financeiro.internal.application.RemoverRegraDeComissaoUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-FIN-002 — a porta da cascata de comissão.
 *
 * <p>Definir e remover exigem {@code config:manage} — comissão é cláusula de contrato, não
 * parâmetro operacional. Ler exige {@code financeiro:read:all}: o percentual de cada um é o
 * contrato de todo mundo.
 */
@RestController
@RequestMapping("/api/v1/regras-de-comissao")
public class RegrasDeComissaoController {

    private final DefinirRegraDeComissaoUseCase definir;
    private final RemoverRegraDeComissaoUseCase remover;
    private final ListarRegrasDeComissaoUseCase listar;
    private final ComissaoApi comissoes;

    public RegrasDeComissaoController(DefinirRegraDeComissaoUseCase definir,
                                      RemoverRegraDeComissaoUseCase remover,
                                      ListarRegrasDeComissaoUseCase listar,
                                      ComissaoApi comissoes) {
        this.definir = definir;
        this.remover = remover;
        this.listar = listar;
        this.comissoes = comissoes;
    }

    /**
     * @param profissionalId só em {@code PROFISSIONAL_SERVICO}
     * @param servicoId      em tudo menos {@code SALAO}
     */
    public record RegraRequest(
            @NotNull EscopoDaRegra escopo,
            UUID profissionalId,
            UUID servicoId,
            @NotNull @PositiveOrZero @DecimalMax("100") BigDecimal percentual) {
    }

    /** {@code PUT} porque definir é substituir: mandar de novo troca o percentual. */
    @PutMapping
    public ResponseEntity<RegraDeComissao> definir(@Valid @RequestBody RegraRequest req) {
        RegraDeComissao regra = definir.executar(req.escopo(), req.profissionalId(),
                req.servicoId(), req.percentual());
        return ResponseEntity.created(URI.create("/api/v1/regras-de-comissao/" + regra.id()))
                .body(regra);
    }

    @GetMapping
    public List<RegraDeComissao> listar() {
        return listar.executar();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remover(@PathVariable UUID id) {
        remover.executar(id);
    }

    /**
     * Simula: <em>quanto esta pessoa ganharia neste serviço, e por qual regra?</em>
     *
     * <p>Existe porque a cascata tem quatro níveis e a pergunta "por que 30%?" é feita antes de o
     * atendimento acontecer, não só depois. Sem isto, quem configura descobre o efeito da regra
     * fechando uma comanda.
     */
    @GetMapping("/simulacao")
    public ComissaoResolvida simular(@RequestParam UUID profissionalId,
                                     @RequestParam UUID servicoId) {
        return comissoes.resolver(profissionalId, servicoId);
    }
}
