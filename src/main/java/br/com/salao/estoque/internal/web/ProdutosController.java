package br.com.salao.estoque.internal.web;

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
import br.com.salao.estoque.internal.application.RegistrarEntradaDeLoteUseCase;
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

    public ProdutosController(CadastrarProdutoUseCase cadastrar, AtualizarProdutoUseCase atualizar,
                              DefinirPrecoDeVendaUseCase definirPreco,
                              DefinirAtivacaoDoProdutoUseCase definirAtivacao,
                              ListarProdutosUseCase listar,
                              RegistrarEntradaDeLoteUseCase registrarEntrada,
                              ListarLotesUseCase listarLotes,
                              ConsultarSaldoUseCase consultarSaldo) {
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
            boolean ativo,
            boolean vendavel,
            int versao) {

        static ProdutoResponse de(ProdutoResumo p) {
            return new ProdutoResponse(p.id(), p.nome(), p.descricao(), p.finalidade(),
                    p.unidade(), p.precoVenda() == null ? null : p.precoVenda().valor(),
                    p.ativo(), p.vendavel(), p.versao());
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
    public ResponseEntity<LoteResumo> registrarEntrada(@PathVariable UUID id,
                                                       @Valid @RequestBody EntradaRequest req) {
        LoteResumo lote = registrarEntrada.executar(id, req.paraDados());
        return ResponseEntity.created(URI.create("/api/v1/produtos/" + id + "/lotes"))
                .body(lote);
    }

    /**
     * Os lotes, <strong>na ordem do FEFO</strong> — vence primeiro, sai primeiro.
     *
     * @param somenteComSaldo padrão {@code true}; {@code false} traz também os zerados, que
     *                        explicam de onde saiu o que já foi vendido
     */
    @GetMapping("/{id}/lotes")
    public List<LoteResumo> lotes(@PathVariable UUID id,
                                  @RequestParam(defaultValue = "true") boolean somenteComSaldo) {
        return listarLotes.executar(id, somenteComSaldo);
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
    public List<SaldoDoProduto> saldos(
            @RequestParam(defaultValue = "false") boolean somenteComEstoque) {
        return consultarSaldo.deTodos(somenteComEstoque);
    }

    /** Quanto há deste produto, e a que custo médio. Nada disso é coluna: tudo é projeção. */
    @GetMapping("/{id}/saldo")
    public ResponseEntity<SaldoDoProduto> saldo(@PathVariable UUID id) {
        return ResponseEntity.of(consultarSaldo.doProduto(id));
    }
}
