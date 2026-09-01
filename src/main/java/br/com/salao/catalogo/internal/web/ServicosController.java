package br.com.salao.catalogo.internal.web;

import br.com.salao.catalogo.api.ServicoResumo;
import br.com.salao.catalogo.api.TipoDeBloco;
import br.com.salao.catalogo.internal.application.CadastrarServicoUseCase;
import br.com.salao.catalogo.internal.application.DefinirAtivacaoDoServicoUseCase;
import br.com.salao.catalogo.internal.application.EtapaBruta;
import br.com.salao.catalogo.internal.application.ListarServicosUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-CAT-001 — o cardápio.
 *
 * <p>Transporte apenas, e sem construir domínio: {@code SequenciaDeBlocos} recusa receita inválida
 * com {@code IllegalArgumentException}, que aqui viraria 500. As etapas viajam cruas até o caso de
 * uso, que traduz — a lição de RT-EQP-002, aplicada antes de o defeito aparecer.
 */
@RestController
@RequestMapping("/api/v1/servicos")
public class ServicosController {

    private final CadastrarServicoUseCase cadastrar;
    private final ListarServicosUseCase listar;
    private final DefinirAtivacaoDoServicoUseCase definirAtivacao;

    public ServicosController(CadastrarServicoUseCase cadastrar, ListarServicosUseCase listar,
                              DefinirAtivacaoDoServicoUseCase definirAtivacao) {
        this.cadastrar = cadastrar;
        this.listar = listar;
        this.definirAtivacao = definirAtivacao;
    }

    /** Uma etapa do serviço. A ordem é a da lista. */
    public record EtapaRequest(@NotNull TipoDeBloco tipo, @NotNull Integer duracaoMinutos) {
    }

    public record CadastrarServicoRequest(
            @NotBlank @Size(max = 200) String nome,
            @Size(max = 1000) String descricao,
            @NotEmpty List<EtapaRequest> etapas) {
    }

    public record AtivacaoRequest(@NotNull Boolean ativo) {
    }

    @GetMapping
    public List<ServicoResumo> listar(
            @RequestParam(defaultValue = "false") boolean incluirInativos) {
        return listar.executar(incluirInativos);
    }

    @PostMapping
    public ResponseEntity<ServicoResumo> cadastrar(
            @Valid @RequestBody CadastrarServicoRequest req) {
        ServicoResumo criado = cadastrar.executar(req.nome(), req.descricao(),
                req.etapas().stream()
                        .map(e -> new EtapaBruta(e.tipo(), e.duracaoMinutos()))
                        .toList());
        return ResponseEntity.created(URI.create("/api/v1/servicos/" + criado.id())).body(criado);
    }

    @PatchMapping("/{id}/ativacao")
    public ServicoResumo definirAtivacao(@PathVariable UUID id,
                                         @Valid @RequestBody AtivacaoRequest req) {
        return definirAtivacao.executar(id, req.ativo());
    }
}
