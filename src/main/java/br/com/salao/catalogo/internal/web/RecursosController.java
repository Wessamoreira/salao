package br.com.salao.catalogo.internal.web;

import br.com.salao.catalogo.api.Recurso;
import br.com.salao.catalogo.internal.application.AtualizarRecursoUseCase;
import br.com.salao.catalogo.internal.application.CadastrarRecursoUseCase;
import br.com.salao.catalogo.internal.application.DefinirAtivacaoDoRecursoUseCase;
import br.com.salao.catalogo.internal.application.ListarRecursosUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-CAT-003 — os recursos do salão.
 *
 * <p>Transporte apenas, e sem construir domínio: {@code Recurso} recusa quantidade zero com
 * {@code IllegalArgumentException}, que aqui viraria 500. A quantidade viaja como {@code Integer}
 * cru até o caso de uso, que traduz.
 */
@RestController
@RequestMapping("/api/v1/recursos")
public class RecursosController {

    private final CadastrarRecursoUseCase cadastrar;
    private final AtualizarRecursoUseCase atualizar;
    private final DefinirAtivacaoDoRecursoUseCase definirAtivacao;
    private final ListarRecursosUseCase listar;

    public RecursosController(CadastrarRecursoUseCase cadastrar, AtualizarRecursoUseCase atualizar,
                              DefinirAtivacaoDoRecursoUseCase definirAtivacao,
                              ListarRecursosUseCase listar) {
        this.cadastrar = cadastrar;
        this.atualizar = atualizar;
        this.definirAtivacao = definirAtivacao;
        this.listar = listar;
    }

    /**
     * {@code quantidade} é {@code Integer} e não {@code int} de propósito: com o primitivo, o
     * campo ausente chegaria como 0 e a mensagem de erro falaria de um número que ninguém digitou.
     */
    public record RecursoRequest(@NotBlank @Size(max = 120) String nome, Integer quantidade) {
    }

    public record AtualizarRecursoRequest(
            @NotBlank @Size(max = 120) String nome, Integer quantidade, @NotNull Integer versao) {
    }

    public record AtivacaoRequest(@NotNull Boolean ativo) {
    }

    @GetMapping
    public List<Recurso> listar(@RequestParam(defaultValue = "false") boolean incluirInativos) {
        return listar.executar(incluirInativos);
    }

    @PostMapping
    public ResponseEntity<Recurso> cadastrar(@Valid @RequestBody RecursoRequest req) {
        Recurso criado = cadastrar.executar(req.nome(), req.quantidade());
        return ResponseEntity.created(URI.create("/api/v1/recursos/" + criado.id())).body(criado);
    }

    @PutMapping("/{id}")
    public Recurso atualizar(@PathVariable UUID id,
                             @Valid @RequestBody AtualizarRecursoRequest req) {
        return atualizar.executar(id, req.versao(), req.nome(), req.quantidade());
    }

    /** Desativar é aposentar, e é recusado enquanto alguma receita ativa exigir o recurso. */
    @PatchMapping("/{id}/ativacao")
    public Recurso definirAtivacao(@PathVariable UUID id,
                                   @Valid @RequestBody AtivacaoRequest req) {
        return definirAtivacao.executar(id, req.ativo());
    }
}
