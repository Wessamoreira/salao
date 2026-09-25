package br.com.salao.catalogo.internal.web;

import br.com.salao.catalogo.api.PrecoVigente;
import br.com.salao.catalogo.api.ServicoApi;
import br.com.salao.catalogo.api.ServicoResumo;
import br.com.salao.catalogo.api.TipoDeBloco;
import br.com.salao.catalogo.internal.application.CadastrarServicoUseCase;
import br.com.salao.catalogo.internal.application.DefinirAtivacaoDoServicoUseCase;
import br.com.salao.catalogo.internal.application.EtapaBruta;
import br.com.salao.catalogo.internal.application.DefinirPrecoUseCase;
import br.com.salao.catalogo.internal.application.EditarServicoUseCase;
import br.com.salao.catalogo.internal.application.ListarCardapioUseCase;
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
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    private final EditarServicoUseCase editar;
    private final ListarServicosUseCase listar;
    private final ListarCardapioUseCase cardapio;
    private final DefinirAtivacaoDoServicoUseCase definirAtivacao;
    private final DefinirPrecoUseCase definirPreco;
    private final RemoverPrecoFuturoUseCase removerPrecoFuturo;
    private final ServicoApi catalogo;

    public ServicosController(CadastrarServicoUseCase cadastrar, EditarServicoUseCase editar,
                              ListarServicosUseCase listar, ListarCardapioUseCase cardapio,
                              DefinirAtivacaoDoServicoUseCase definirAtivacao,
                              DefinirPrecoUseCase definirPreco,
                              RemoverPrecoFuturoUseCase removerPrecoFuturo,
                              ServicoApi catalogo) {
        this.cadastrar = cadastrar;
        this.editar = editar;
        this.listar = listar;
        this.cardapio = cardapio;
        this.definirAtivacao = definirAtivacao;
        this.definirPreco = definirPreco;
        this.removerPrecoFuturo = removerPrecoFuturo;
        this.catalogo = catalogo;
    }

    /**
     * Uma etapa do serviço. A ordem é a da lista.
     *
     * <p>{@code recursosExigidos} (RT-CAT-003) é por etapa e não por serviço: a coloração ocupa o
     * lavatório só na lavagem, e exigi-lo no serviço inteiro reservaria 100 minutos de um
     * equipamento usado por 30.
     */
    public record EtapaRequest(@NotNull TipoDeBloco tipo, @NotNull Integer duracaoMinutos,
                               Set<UUID> recursosExigidos) {
    }

    public record CadastrarServicoRequest(
            @NotBlank @Size(max = 200) String nome,
            @Size(max = 1000) String descricao,
            @NotEmpty List<EtapaRequest> etapas) {
    }

    /**
     * RT-CAT-005 — a receita inteira, e a versão que a tela leu.
     *
     * <p>A receita vai completa em vez de por diferença: "troque a etapa 2 para 45 minutos" exigiria
     * que os dois lados concordassem sobre qual é a etapa 2, e duas pessoas editando ao mesmo tempo
     * não concordam. A versão resolve isso de uma vez — quem chegou depois é recusado.
     */
    public record EditarServicoRequest(
            @NotBlank @Size(max = 200) String nome,
            @Size(max = 1000) String descricao,
            @NotNull Integer versao,
            @NotEmpty List<EtapaRequest> etapas) {
    }

    public record AtivacaoRequest(@NotNull Boolean ativo) {
    }

    /**
     * RT-CAT-005 — o serviço como a tela precisa dele.
     *
     * <p>Existe por dois motivos. O primeiro: {@code duracaoTotal} e {@code minutosDoProfissional}
     * são métodos de {@link ServicoResumo}, e método não vira campo no JSON — a tela receberia só
     * os blocos e teria de somar. Somar é fácil; separar o que ocupa a profissional do que não
     * ocupa é <strong>regra de domínio</strong> (RN-CAT-001), e uma segunda cópia dela em
     * JavaScript diria um dia uma coisa enquanto a agenda fazia outra.
     *
     * <p>O segundo: a receita chega como {@code etapas} no pedido e voltava como
     * {@code blocos.blocos} na resposta. Mesma coisa, dois nomes e um aninhamento a mais.
     */
    public record ServicoNaTela(UUID id, String nome, String descricao, boolean ativo, int versao,
                                int duracaoTotal, int minutosDoProfissional,
                                BigDecimal precoVigente, List<EtapaRequest> etapas) {

        /**
         * {@code precoVigente} nulo é serviço que ninguém precificou — e é a informação mais
         * importante da lista, porque é o serviço que a comanda vai recusar vender (RN-CAT-007).
         * Zero seria um número errado dito com confiança.
         */
        static ServicoNaTela de(ServicoResumo servico, Money preco) {
            return new ServicoNaTela(servico.id(), servico.nome(), servico.descricao(),
                    servico.ativo(), servico.versao(),
                    servico.duracaoTotal(), servico.minutosDoProfissional(),
                    preco == null ? null : preco.valor(),
                    servico.blocos().blocos().stream()
                            .map(b -> new EtapaRequest(b.tipo(), b.duracaoMinutos(),
                                    b.recursosExigidos()))
                            .toList());
        }
    }

    @GetMapping
    public List<ServicoNaTela> listar(
            @RequestParam(defaultValue = "false") boolean incluirInativos) {
        return cardapio.executar(incluirInativos).stream()
                .map(item -> ServicoNaTela.de(item.servico(), item.precoVigente()))
                .toList();
    }

    @PostMapping
    public ResponseEntity<ServicoNaTela> cadastrar(
            @Valid @RequestBody CadastrarServicoRequest req) {
        ServicoResumo criado = cadastrar.executar(req.nome(), req.descricao(),
                req.etapas().stream()
                        .map(e -> new EtapaBruta(e.tipo(), e.duracaoMinutos(),
                                e.recursosExigidos()))
                        .toList());
        return ResponseEntity.created(URI.create("/api/v1/servicos/" + criado.id()))
                .body(ServicoNaTela.de(criado, null));
    }

    @PutMapping("/{id}")
    public ServicoNaTela editar(@PathVariable UUID id,
                                @Valid @RequestBody EditarServicoRequest req) {
        return ServicoNaTela.de(editar.executar(id, req.nome(), req.descricao(),
                req.etapas().stream()
                        .map(e -> new EtapaBruta(e.tipo(), e.duracaoMinutos(),
                                e.recursosExigidos()))
                        .toList(),
                req.versao()), null);
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
    public ServicoNaTela definirAtivacao(@PathVariable UUID id,
                                         @Valid @RequestBody AtivacaoRequest req) {
        return ServicoNaTela.de(definirAtivacao.executar(id, req.ativo()), null);
    }
}
