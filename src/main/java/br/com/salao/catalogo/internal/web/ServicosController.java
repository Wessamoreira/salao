package br.com.salao.catalogo.internal.web;

import br.com.salao.catalogo.api.PrecoVigente;
import br.com.salao.catalogo.api.ServicoApi;
import br.com.salao.catalogo.api.ServicoResumo;
import br.com.salao.catalogo.api.TipoDeBloco;
import br.com.salao.catalogo.internal.application.CadastrarServicoUseCase;
import br.com.salao.catalogo.internal.application.DefinirAtivacaoDoServicoUseCase;
import br.com.salao.catalogo.internal.application.EtapaBruta;
import br.com.salao.catalogo.internal.application.DefinirPrecoUseCase;
import br.com.salao.catalogo.internal.application.ListarServicosUseCase;
import br.com.salao.catalogo.internal.application.RemoverPrecoFuturoUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import br.com.salao.shared.dinheiro.Money;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
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
    private final DefinirPrecoUseCase definirPreco;
    private final RemoverPrecoFuturoUseCase removerPrecoFuturo;
    private final ServicoApi catalogo;

    public ServicosController(CadastrarServicoUseCase cadastrar, ListarServicosUseCase listar,
                              DefinirAtivacaoDoServicoUseCase definirAtivacao,
                              DefinirPrecoUseCase definirPreco,
                              RemoverPrecoFuturoUseCase removerPrecoFuturo,
                              ServicoApi catalogo) {
        this.cadastrar = cadastrar;
        this.listar = listar;
        this.definirAtivacao = definirAtivacao;
        this.definirPreco = definirPreco;
        this.removerPrecoFuturo = removerPrecoFuturo;
        this.catalogo = catalogo;
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

    /**
     * RT-CAT-002 — define o preço a partir de uma data.
     *
     * <p>{@code vigenteDe} ausente significa "a partir de hoje", no fuso do salão. Data futura é o
     * uso normal: anunciar o aumento antes de aplicá-lo.
     */
    public record PrecoRequest(@NotNull BigDecimal valor, LocalDate vigenteDe) {
    }

    @PostMapping("/{id}/precos")
    public ResponseEntity<PrecoVigente> definirPreco(@PathVariable UUID id,
                                                     @Valid @RequestBody PrecoRequest req) {
        PrecoVigente criado = definirPreco.executar(id, new Money(req.valor()), req.vigenteDe());
        return ResponseEntity.created(
                URI.create("/api/v1/servicos/" + id + "/precos/" + criado.id())).body(criado);
    }

    /** O histórico, do mais recente para o mais antigo. É a prova de quanto custava em cada época. */
    @GetMapping("/{id}/precos")
    public List<PrecoVigente> historicoDePrecos(@PathVariable UUID id) {
        return catalogo.historicoDePrecos(id);
    }

    /**
     * Quanto custava numa data. Sem preço definido até ali, devolve 404 — e não zero, que seria um
     * número errado num relatório financeiro.
     */
    @GetMapping("/{id}/preco")
    public Money precoEm(@PathVariable UUID id,
                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                         LocalDate data) {
        return catalogo.precoEm(id, data).orElseThrow(() -> new br.com.salao.shared.erro
                .ErroDeDominio(br.com.salao.catalogo.api.ErrosDoCatalogo.SERVICO_NAO_ENCONTRADO,
                        "sem preço definido para o serviço " + id + " em " + data));
    }

    @DeleteMapping("/{id}/precos/{precoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelarPrecoFuturo(@PathVariable UUID id, @PathVariable UUID precoId) {
        removerPrecoFuturo.executar(precoId);
    }

    @PatchMapping("/{id}/ativacao")
    public ServicoResumo definirAtivacao(@PathVariable UUID id,
                                         @Valid @RequestBody AtivacaoRequest req) {
        return definirAtivacao.executar(id, req.ativo());
    }
}
