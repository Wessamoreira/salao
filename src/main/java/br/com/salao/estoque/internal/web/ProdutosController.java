package br.com.salao.estoque.internal.web;

import br.com.salao.estoque.internal.application.ConsultarAlertasUseCase;
import br.com.salao.estoque.internal.application.DefinirCodigoDeBarrasUseCase;
import br.com.salao.estoque.internal.application.DefinirEstoqueMinimoUseCase;
import br.com.salao.shared.erro.ErroDeDominio;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import br.com.salao.estoque.api.ProdutoApi;
import br.com.salao.estoque.api.ErrosDoEstoque;
import br.com.salao.estoque.api.AlertasDeEstoque;
import br.com.salao.estoque.api.FinalidadeDoProduto;
import br.com.salao.estoque.api.LoteResumo;
import br.com.salao.estoque.api.ProdutoResumo;
import br.com.salao.estoque.api.SaldoDoProduto;
import br.com.salao.estoque.api.UnidadeDeMedida;
import br.com.salao.estoque.internal.application.AtualizarProdutoUseCase;
import br.com.salao.estoque.internal.application.CadastrarProdutoUseCase;
import br.com.salao.estoque.internal.application.DadosDoProduto;
import br.com.salao.estoque.internal.application.DefinirAtivacaoDoProdutoUseCase;
import br.com.salao.estoque.internal.application.DefinirPrecoDeVendaUseCase;
import br.com.salao.estoque.internal.application.ConsultarSaldoUseCase;
import br.com.salao.estoque.internal.application.DadosDaEntrada;
import br.com.salao.estoque.internal.application.ListarLotesUseCase;
import br.com.salao.estoque.internal.application.ListarProdutosUseCase;
import br.com.salao.estoque.api.BaixaDeEstoque;
import br.com.salao.estoque.internal.application.RegistrarEntradaDeLoteUseCase;
import br.com.salao.estoque.internal.application.RegistrarPerdaUseCase;
import br.com.salao.shared.dinheiro.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
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
 * RT-EST-001 — a porta do cadastro de produto.
 *
 * <p>O preço tem rota própria porque tem permissão própria: a recepção cadastra e não precifica.
 * Misturá-lo no corpo do cadastro obrigaria a rota inteira a exigir a permissão de preço — e a
 * recepção deixaria de conseguir cadastrar.
 */
@RestController
@RequestMapping("/api/v1/produtos")
public class ProdutosController {

    private final CadastrarProdutoUseCase cadastrar;
    private final AtualizarProdutoUseCase atualizar;
    private final DefinirPrecoDeVendaUseCase definirPreco;
    private final DefinirAtivacaoDoProdutoUseCase definirAtivacao;
    private final ListarProdutosUseCase listar;
    private final RegistrarEntradaDeLoteUseCase registrarEntrada;
    private final ListarLotesUseCase listarLotes;
    private final ConsultarSaldoUseCase consultarSaldo;
    private final DefinirCodigoDeBarrasUseCase definirCodigoDeBarras;
    private final DefinirEstoqueMinimoUseCase definirEstoqueMinimo;
    private final ConsultarAlertasUseCase alertas;
    private final RegistrarPerdaUseCase registrarPerda;
    private final ProdutoApi produtos;

    public ProdutosController(CadastrarProdutoUseCase cadastrar, AtualizarProdutoUseCase atualizar,
                              DefinirPrecoDeVendaUseCase definirPreco,
                              DefinirAtivacaoDoProdutoUseCase definirAtivacao,
                              ListarProdutosUseCase listar,
                              RegistrarEntradaDeLoteUseCase registrarEntrada,
                              ListarLotesUseCase listarLotes,
                              ConsultarSaldoUseCase consultarSaldo,
                              DefinirCodigoDeBarrasUseCase definirCodigoDeBarras,
                              DefinirEstoqueMinimoUseCase definirEstoqueMinimo,
                              ConsultarAlertasUseCase alertas,
                              RegistrarPerdaUseCase registrarPerda,
                              ProdutoApi produtos) {
        this.definirCodigoDeBarras = definirCodigoDeBarras;
        this.definirEstoqueMinimo = definirEstoqueMinimo;
        this.alertas = alertas;
        this.registrarPerda = registrarPerda;
        this.produtos = produtos;
        this.cadastrar = cadastrar;
        this.atualizar = atualizar;
        this.definirPreco = definirPreco;
        this.definirAtivacao = definirAtivacao;
        this.listar = listar;
        this.registrarEntrada = registrarEntrada;
        this.listarLotes = listarLotes;
        this.consultarSaldo = consultarSaldo;
    }

    /**
     * O produto como a tela o recebe.
     *
     * <p>Existe para carregar {@code vendavel}, que o {@code ProdutoResumo} calcula e o Jackson não
     * serializa — método derivado de record não vira campo. Deixar o front compor
     * "ativo e não é consumo interno e tem preço" recriaria no JavaScript a regra que mora aqui, e
     * as duas cópias divergiriam na primeira mudança. É a mesma razão de {@code Capacidades}
     * derivar menus no servidor (RT-IAM-006).
     */
    public record ProdutoResponse(
            UUID id,
            String nome,
            String descricao,
            FinalidadeDoProduto finalidade,
            UnidadeDeMedida unidade,
            BigDecimal precoVenda,
            String codigoBarras,
            boolean ativo,
            boolean vendavel,
            int versao) {

        static ProdutoResponse de(ProdutoResumo p) {
            return new ProdutoResponse(p.id(), p.nome(), p.descricao(), p.finalidade(),
                    p.unidade(), p.precoVenda() == null ? null : p.precoVenda().valor(),
                    p.codigoBarras(), p.ativo(), p.vendavel(), p.versao());
        }
    }

    /** {@code precoVenda} é opcional, e informá-lo exige também a permissão de preço. */
    public record ProdutoRequest(
            @NotBlank @Size(max = 200) String nome,
            @Size(max = 1000) String descricao,
            @NotNull FinalidadeDoProduto finalidade,
            @NotNull UnidadeDeMedida unidade,
            BigDecimal precoVenda) {

        DadosDoProduto paraDados() {
            return new DadosDoProduto(nome, descricao, finalidade, unidade,
                    precoVenda == null ? null : new Money(precoVenda));
        }
    }

    @PostMapping
    public ResponseEntity<ProdutoResponse> cadastrar(@Valid @RequestBody ProdutoRequest req) {
        ProdutoResumo produto = cadastrar.executar(req.paraDados());
        return ResponseEntity.created(URI.create("/api/v1/produtos/" + produto.id()))
                .body(ProdutoResponse.de(produto));
    }

    /**
     * @param versao a versão que a tela carregou; se não bater, alguém salvou por cima
     */
    @PutMapping("/{id}")
    public ProdutoResponse atualizar(@PathVariable UUID id, @RequestParam int versao,
                                     @Valid @RequestBody ProdutoRequest req) {
        return ProdutoResponse.de(atualizar.executar(id, req.paraDados(), versao));
    }

    /** {@code valor} nulo remove o preço: o produto continua existindo e deixa de ser vendável. */
    public record PrecoRequest(BigDecimal valor) {
    }

    @PutMapping("/{id}/preco")
    public ProdutoResponse definirPreco(@PathVariable UUID id, @RequestBody PrecoRequest req) {
        return ProdutoResponse.de(definirPreco.executar(id,
                req.valor() == null ? null : new Money(req.valor())));
    }

    public record AtivacaoRequest(@NotNull Boolean ativo) {
    }

    /**
     * RT-EST-009 — o produto que o leitor bipou.
     *
     * <p>Caminho literal antes do {@code /{id}}: o Spring prefere o mapeamento mais específico, e
     * "por-codigo-barras" nunca é convertido para UUID.
     *
     * <p>404 quando não existe, e não uma lista vazia: quem bipou precisa saber que aquele código
     * não está cadastrado, e uma lista de zero itens obriga o front a interpretar.
     *
     * <p>Devolve o mesmo {@code ProdutoResponse} das outras rotas. Devolver o {@code ProdutoResumo}
     * cru daria à tela DUAS formas do mesmo produto — o {@code Money} do domínio serializa como
     * {@code {"valor": …}}, e o resto da API manda número.
     */
    @GetMapping("/por-codigo-barras")
    public ProdutoResponse porCodigoDeBarras(@RequestParam String codigo) {
        return ProdutoResponse.de(produtos.porCodigoDeBarras(codigo)
                .orElseThrow(() -> new ErroDeDominio(
                        ErrosDoEstoque.CODIGO_DE_BARRAS_DESCONHECIDO,
                        "nenhum produto com o código " + codigo)));
    }

    /** O código de barras do produto. Corpo vazio ou nulo remove. */
    @PutMapping("/{id}/codigo-barras")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void definirCodigoDeBarras(@PathVariable UUID id,
                                      @RequestBody CodigoRequest req) {
        definirCodigoDeBarras.executar(id, req == null ? null : req.codigo());
    }

    /** Sem {@code @NotBlank}: vazio é o pedido de REMOVER o código. */
    public record CodigoRequest(@jakarta.validation.constraints.Size(max = 64) String codigo) {
    }

    /** O estoque mínimo do produto. Corpo com {@code null} remove — nulo não é zero. */
    @PutMapping("/{id}/estoque-minimo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void definirEstoqueMinimo(@PathVariable UUID id,
                                     @RequestBody MinimoRequest req) {
        definirEstoqueMinimo.executar(id, req == null ? null : req.minimo());
    }

    public record MinimoRequest(java.math.BigDecimal minimo) {
    }

    /** RT-EST-008 — o que está acabando e o que vai vencer. */
    @GetMapping("/alertas")
    public AlertasResponse alertas(@RequestParam(required = false) Integer dias) {
        return AlertasResponse.de(alertas.executar(dias));
    }

    @PatchMapping("/{id}/ativacao")
    public ProdutoResponse definirAtivacao(@PathVariable UUID id,
                                           @Valid @RequestBody AtivacaoRequest req) {
        return ProdutoResponse.de(definirAtivacao.executar(id, req.ativo()));
    }

    /**
     * @param somenteAtivos    padrão {@code true}: a tela de cadastro pede {@code false} para ver
     *                         o que foi desativado
     * @param somenteVendaveis a lista do balcão. Sem ele, a tela de venda receberia tinta
     */
    @GetMapping
    public List<ProdutoResponse> listar(
            @RequestParam(defaultValue = "true") boolean somenteAtivos,
            @RequestParam(defaultValue = "false") boolean somenteVendaveis) {
        return listar.executar(somenteAtivos, somenteVendaveis).stream()
                .map(ProdutoResponse::de).toList();
    }

    /**
     * O saldo como a tela o recebe.
     *
     * <p>{@code Money} serializa como {@code {"valor": …}} e {@code semEstoque()} é método
     * derivado, que o Jackson não vê. A rota devolvia o registro de domínio cru — duas formas do
     * mesmo dinheiro na mesma API, que é exatamente o que {@link ProdutoResponse} existe para
     * evitar. Nenhuma tela consumia isto ainda.
     *
     * @param custoMedio <strong>nulo</strong> quando não há estoque, e nulo não é zero: zero
     *                   diria "tenho unidades que não custaram nada", que é o caso da amostra
     */
    public record SaldoResponse(
            UUID produtoId,
            String nome,
            UnidadeDeMedida unidade,
            BigDecimal quantidade,
            BigDecimal custoMedio,
            BigDecimal valorEmEstoque,
            boolean semEstoque) {

        static SaldoResponse de(SaldoDoProduto s) {
            return new SaldoResponse(s.produtoId(), s.nome(), s.unidade(), s.quantidade(),
                    s.custoMedio() == null ? null : s.custoMedio().valor(),
                    s.valorEmEstoque().valor(), s.semEstoque());
        }
    }

    /** O lote como a tela o recebe. Mesmo motivo de {@link SaldoResponse}. */
    public record LoteResponse(
            UUID id,
            UUID produtoId,
            String codigo,
            BigDecimal custoUnitario,
            BigDecimal quantidade,
            LocalDate validade,
            LocalDate recebidoEm) {

        static LoteResponse de(LoteResumo l) {
            return new LoteResponse(l.id(), l.produtoId(), l.codigo(),
                    l.custoUnitario().valor(), l.quantidade(), l.validade(), l.recebidoEm());
        }
    }

    /**
     * Os alertas como a tela os recebe.
     *
     * <p>{@code faltando()} é o número do pedido de compra e {@code custoEmRisco()} é o que faz o
     * dono agir — os dois são métodos derivados, e sem DTO simplesmente não chegavam.
     */
    public record AlertasResponse(
            int diasDeAviso,
            BigDecimal custoEmRisco,
            List<FaltaResponse> abaixoDoMinimo,
            List<VencendoResponse> vencendo) {

        static AlertasResponse de(AlertasDeEstoque a) {
            return new AlertasResponse(a.diasDeAviso(), a.custoEmRisco().valor(),
                    a.abaixoDoMinimo().stream().map(FaltaResponse::de).toList(),
                    a.vencendo().stream().map(VencendoResponse::de).toList());
        }
    }

    public record FaltaResponse(
            UUID produtoId,
            String nome,
            UnidadeDeMedida unidade,
            BigDecimal quantidade,
            BigDecimal minimo,
            BigDecimal faltando,
            boolean zerado) {

        static FaltaResponse de(AlertasDeEstoque.ProdutoEmFalta p) {
            return new FaltaResponse(p.produtoId(), p.nome(), p.unidade(), p.quantidade(),
                    p.minimo(), p.faltando(), p.zerado());
        }
    }

    public record VencendoResponse(
            UUID loteId,
            UUID produtoId,
            String produto,
            UnidadeDeMedida unidade,
            String codigo,
            BigDecimal quantidade,
            LocalDate validade,
            long diasRestantes,
            BigDecimal custoEmRisco,
            boolean vencido) {

        static VencendoResponse de(AlertasDeEstoque.LoteVencendo l) {
            return new VencendoResponse(l.loteId(), l.produtoId(), l.produto(), l.unidade(),
                    l.codigo(), l.quantidade(), l.validade(), l.diasRestantes(),
                    l.custoEmRisco().valor(), l.vencido());
        }
    }

    // ------------------------------------------------------------------ RT-EST-005

    /**
     * @param quantidade positiva: o sinal é do caso de uso, que sabe que perda é saída
     * @param motivo obrigatório (RN-EST-014), conferido aqui e pelo banco
     */
    public record PerdaRequest(
            @NotNull @jakarta.validation.constraints.Positive BigDecimal quantidade,
            @Size(max = 500) String motivo) {
    }

    /**
     * A perda é <strong>do produto</strong>, e o FEFO escolhe os lotes.
     *
     * <p>Quem viu o xampu vazar no chão sabe o produto e a quantidade, não o lote. Exigir o lote
     * faria alguém escolher um qualquer para poder continuar — e o número certo entraria no lugar
     * errado. Quando o lote É sabido, o caminho é o ajuste ({@code POST /lotes/{id}/ajustes}).
     */
    @PostMapping("/{id}/perdas")
    public BaixaResponse registrarPerda(@PathVariable UUID id,
                                        @Valid @RequestBody PerdaRequest req) {
        BaixaDeEstoque baixa = registrarPerda.executar(id, req.quantidade(), req.motivo());
        return new BaixaResponse(baixa.quantidade(), baixa.custoTotal().valor(),
                baixa.parcelas().size());
    }

    /** @param lotesAtingidos de quantas caixas diferentes a perda saiu */
    public record BaixaResponse(BigDecimal quantidade, BigDecimal custoTotal,
                                int lotesAtingidos) {
    }

    // ------------------------------------------------------------------ RT-EST-002

    /**
     * @param custoUnitario zero é válido: amostra e brinde do fornecedor chegam sem custar nada
     * @param validade      nula quando o produto não tem validade impressa
     * @param recebidoEm    nula vira hoje — a nota costuma ser lançada dias depois
     */
    public record EntradaRequest(
            @NotNull @jakarta.validation.constraints.Positive BigDecimal quantidade,
            @NotNull @jakarta.validation.constraints.PositiveOrZero BigDecimal custoUnitario,
            @Size(max = 60) String codigo,
            LocalDate validade,
            LocalDate recebidoEm) {

        DadosDaEntrada paraDados() {
            return new DadosDaEntrada(quantidade, new Money(custoUnitario), codigo, validade,
                    recebidoEm);
        }
    }

    /** A caixa chegou: cria o lote e o movimento de entrada, numa transação. */
    @PostMapping("/{id}/lotes")
    public ResponseEntity<LoteResponse> registrarEntrada(@PathVariable UUID id,
                                                         @Valid @RequestBody EntradaRequest req) {
        LoteResumo lote = registrarEntrada.executar(id, req.paraDados());
        return ResponseEntity.created(URI.create("/api/v1/produtos/" + id + "/lotes"))
                .body(LoteResponse.de(lote));
    }

    /**
     * Os lotes, <strong>na ordem do FEFO</strong> — vence primeiro, sai primeiro.
     *
     * @param somenteComSaldo padrão {@code true}; {@code false} traz também os zerados, que
     *                        explicam de onde saiu o que já foi vendido
     */
    @GetMapping("/{id}/lotes")
    public List<LoteResponse> lotes(@PathVariable UUID id,
                                    @RequestParam(defaultValue = "true") boolean somenteComSaldo) {
        return listarLotes.executar(id, somenteComSaldo).stream().map(LoteResponse::de).toList();
    }

    // ------------------------------------------------------------------ RT-EST-003

    /**
     * O inventário: o que há de cada produto e quanto vale.
     *
     * <p>Rota literal antes de {@code /{id}} de propósito — {@code /saldos} é um caminho, não um
     * uuid.
     *
     * @param somenteComEstoque padrão {@code false}: a tela de reposição existe justamente para
     *                          mostrar o que está zerado
     */
    @GetMapping("/saldos")
    public List<SaldoResponse> saldos(
            @RequestParam(defaultValue = "false") boolean somenteComEstoque) {
        return consultarSaldo.deTodos(somenteComEstoque).stream()
                .map(SaldoResponse::de).toList();
    }

    /** Quanto há deste produto, e a que custo médio. Nada disso é coluna: tudo é projeção. */
    @GetMapping("/{id}/saldo")
    public ResponseEntity<SaldoResponse> saldo(@PathVariable UUID id) {
        return ResponseEntity.of(consultarSaldo.doProduto(id).map(SaldoResponse::de));
    }
}
