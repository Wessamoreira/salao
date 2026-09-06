package br.com.salao.atendimento.internal.web;

import br.com.salao.atendimento.api.AberturaDeComanda;
import br.com.salao.atendimento.api.ComandaDetalhada;
import br.com.salao.atendimento.api.ContaDaComanda;
import br.com.salao.financeiro.api.DadosDoPagamento;
import br.com.salao.financeiro.api.FormaDePagamento;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.atendimento.internal.application.AbrirComandaUseCase;
import br.com.salao.atendimento.internal.application.AdicionarItemDeProdutoUseCase;
import br.com.salao.atendimento.internal.application.AdicionarItemDeServicoUseCase;
import br.com.salao.atendimento.internal.application.AdicionarExecutanteUseCase;
import br.com.salao.atendimento.internal.application.AplicarDescontoUseCase;
import br.com.salao.atendimento.internal.application.RemoverExecutanteUseCase;
import br.com.salao.atendimento.internal.application.ConsultarComandaUseCase;
import br.com.salao.atendimento.internal.application.ConsultarContaUseCase;
import br.com.salao.atendimento.internal.application.RegistrarPagamentoUseCase;
import br.com.salao.atendimento.internal.application.RemoverPagamentoUseCase;
import br.com.salao.atendimento.internal.application.RemoverItemUseCase;
import br.com.salao.atendimento.internal.application.ListarComandasAbertasUseCase;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-ATD-001 — a porta da comanda.
 *
 * <h2>Reabrir a mesma comanda responde 200, não 201</h2>
 *
 * <p>201 significa "criei". Quando a cliente já tinha comanda aberta, nada foi criado — e é uma
 * diferença que a tela precisa ver, porque anunciar "comanda 42 aberta" para uma comanda que está
 * aberta desde as nove da manhã, com itens dentro, é mentir para quem está no balcão.
 */
@RestController
@RequestMapping("/api/v1/comandas")
public class ComandasController {

    private final AbrirComandaUseCase abrir;
    private final ListarComandasAbertasUseCase abertas;
    private final AdicionarItemDeServicoUseCase adicionarServico;
    private final AdicionarItemDeProdutoUseCase adicionarProduto;
    private final RegistrarPagamentoUseCase registrarPagamento;
    private final RemoverPagamentoUseCase removerPagamento;
    private final ConsultarContaUseCase consultarConta;
    private final RemoverItemUseCase removerItem;
    private final AplicarDescontoUseCase aplicarDesconto;
    private final AdicionarExecutanteUseCase adicionarExecutante;
    private final RemoverExecutanteUseCase removerExecutante;
    private final ConsultarComandaUseCase consultar;

    public ComandasController(AbrirComandaUseCase abrir, ListarComandasAbertasUseCase abertas,
                              AdicionarItemDeServicoUseCase adicionarServico,
                              AdicionarItemDeProdutoUseCase adicionarProduto,
                              RemoverItemUseCase removerItem, ConsultarComandaUseCase consultar,
                              AplicarDescontoUseCase aplicarDesconto,
                              AdicionarExecutanteUseCase adicionarExecutante,
                              RemoverExecutanteUseCase removerExecutante,
                              RegistrarPagamentoUseCase registrarPagamento,
                              RemoverPagamentoUseCase removerPagamento,
                              ConsultarContaUseCase consultarConta) {
        this.abrir = abrir;
        this.abertas = abertas;
        this.adicionarServico = adicionarServico;
        this.adicionarProduto = adicionarProduto;
        this.removerItem = removerItem;
        this.consultar = consultar;
        this.aplicarDesconto = aplicarDesconto;
        this.adicionarExecutante = adicionarExecutante;
        this.removerExecutante = removerExecutante;
        this.registrarPagamento = registrarPagamento;
        this.removerPagamento = removerPagamento;
        this.consultarConta = consultarConta;
    }

    /**
     * Um campo ou o outro, nunca os dois.
     *
     * <p>Duas rotas seriam a alternativa, e o balcão faz uma coisa só — abrir a conta de quem
     * chegou. O que muda é o que a recepção tem na mão: o horário marcado, ou só a pessoa.
     */
    public record AbrirRequest(UUID agendamentoId, UUID clienteId) {

        @jakarta.validation.constraints.AssertTrue(
                message = "informe agendamentoId ou clienteId, não os dois")
        public boolean isExatamenteUm() {
            return (agendamentoId == null) != (clienteId == null);
        }
    }

    @PostMapping
    public ResponseEntity<AberturaDeComanda> abrir(@Valid @RequestBody AbrirRequest req) {
        AberturaDeComanda abertura = req.agendamentoId() != null
                ? abrir.doAgendamento(req.agendamentoId())
                : abrir.avulsa(req.clienteId());

        URI local = URI.create("/api/v1/comandas/" + abertura.comanda().id());
        return abertura.jaEstavaAberta()
                ? ResponseEntity.ok().location(local).body(abertura)
                : ResponseEntity.created(local).body(abertura);
    }

    /** A barra do balcão (R-UX-07). Sempre as abertas — não é listagem geral de comandas. */
    @GetMapping("/abertas")
    public List<ListarComandasAbertasUseCase.ComandaEmAberto> abertas() {
        return abertas.executar();
    }

    // ------------------------------------------------------------------ RT-ATD-002

    /** A comanda com tudo que já foi lançado — a tela de fechamento. */
    @GetMapping("/{id}")
    public ResponseEntity<ComandaDetalhada> porId(@PathVariable UUID id) {
        return ResponseEntity.of(consultar.executar(id));
    }

    /**
     * @param quantidade nula vira 1; o caso normal é um serviço, uma vez
     * @param agendamentoId opcional — de qual horário marcado veio este item
     */
    public record ItemDeServicoRequest(
            @jakarta.validation.constraints.NotNull UUID servicoId,
            @jakarta.validation.constraints.NotNull UUID profissionalId,
            @jakarta.validation.constraints.Positive BigDecimal quantidade,
            UUID agendamentoId) {
    }

    /**
     * Devolve a comanda inteira, não só o item criado.
     *
     * <p>Depois de lançar, o que a tela precisa mostrar é o total novo. Devolver só o item
     * obrigaria o front a somar por conta — e duas somas, a da tela e a do banco, divergem no dia
     * em que alguém arredondar diferente.
     */
    @PostMapping("/{id}/itens")
    public ResponseEntity<ComandaDetalhada> adicionarServico(
            @PathVariable UUID id, @Valid @RequestBody ItemDeServicoRequest req) {
        ComandaDetalhada comanda = adicionarServico.executar(id, req.servicoId(),
                req.profissionalId(), req.quantidade(), req.agendamentoId());
        return ResponseEntity.created(URI.create("/api/v1/comandas/" + id)).body(comanda);
    }

    /** Remover é reversível (R-UX-05): executa na hora, e a tela oferece "Desfazer". */
    @DeleteMapping("/{id}/itens/{itemId}")
    public ComandaDetalhada removerItem(@PathVariable UUID id, @PathVariable UUID itemId) {
        return removerItem.executar(id, itemId);
    }

    /**
     * Valor <strong>ou</strong> percentual — são duas formas de dizer a mesma coisa, e aceitar as
     * duas juntas deixaria a resposta para a ordem do {@code if}.
     */
    public record DescontoRequest(BigDecimal valor, BigDecimal percentual) {

        @jakarta.validation.constraints.AssertTrue(
                message = "informe o desconto em valor ou em percentual, não os dois")
        public boolean isExatamenteUm() {
            return (valor == null) != (percentual == null);
        }
    }

    /**
     * {@code PUT} porque define o desconto da linha, e não o acumula: mandar duas vezes o mesmo
     * valor deixa a comanda no mesmo estado. Zero remove.
     */
    @PutMapping("/{id}/itens/{itemId}/desconto")
    public ComandaDetalhada aplicarDesconto(@PathVariable UUID id, @PathVariable UUID itemId,
                                            @Valid @RequestBody DescontoRequest req) {
        return aplicarDesconto.executar(id, itemId,
                req.valor() == null ? null : new Money(req.valor()), req.percentual());
    }

    // ------------------------------------------------------------------ RT-ATD-005

    /**
     * @param percentual em pontos percentuais ({@code 10} é 10%). Obrigatório: o assistente não
     *                   tem percentual de cadastro para herdar — ver o caso de uso
     */
    public record ExecutanteRequest(
            @jakarta.validation.constraints.NotNull UUID profissionalId,
            @jakarta.validation.constraints.NotNull BigDecimal percentual) {
    }

    /** Acrescenta um assistente ao item. O principal entra com o item (RT-ATD-002). */
    @PostMapping("/{id}/itens/{itemId}/executantes")
    public ResponseEntity<ComandaDetalhada> adicionarExecutante(
            @PathVariable UUID id, @PathVariable UUID itemId,
            @Valid @RequestBody ExecutanteRequest req) {
        ComandaDetalhada comanda = adicionarExecutante.executar(id, itemId, req.profissionalId(),
                req.percentual());
        return ResponseEntity.created(URI.create("/api/v1/comandas/" + id)).body(comanda);
    }

    @DeleteMapping("/{id}/itens/{itemId}/executantes/{executanteId}")
    public ComandaDetalhada removerExecutante(@PathVariable UUID id, @PathVariable UUID itemId,
                                              @PathVariable UUID executanteId) {
        return removerExecutante.executar(id, itemId, executanteId);
    }

    // ------------------------------------------------------------------ RT-ATD-003

    public record ItemDeProdutoRequest(
            @jakarta.validation.constraints.NotNull UUID produtoId,
            @jakarta.validation.constraints.Positive BigDecimal quantidade) {
    }

    /**
     * Rota separada da de serviço, e não um campo {@code tipo} no mesmo corpo.
     *
     * <p>Os dois pedem coisas diferentes — serviço exige quem executou, produto não tem executante
     * — e um corpo só teria metade dos campos ignorados conforme o tipo, com a validação virando
     * {@code if}. Duas rotas dizem no endereço o que cada uma precisa.
     */
    @PostMapping("/{id}/produtos")
    public ResponseEntity<ComandaDetalhada> adicionarProduto(
            @PathVariable UUID id, @Valid @RequestBody ItemDeProdutoRequest req) {
        ComandaDetalhada comanda = adicionarProduto.executar(id, req.produtoId(),
                req.quantidade());
        return ResponseEntity.created(URI.create("/api/v1/comandas/" + id)).body(comanda);
    }

    // ------------------------------------------------------------------ RT-FIN-001

    /**
     * @param parcelas só em crédito; nulo vira 1
     * @param nsu e {@code autorizacao} vêm da maquininha, e são o que a conciliação casa depois
     */
    public record PagamentoRequest(
            @jakarta.validation.constraints.NotNull FormaDePagamento forma,
            @jakarta.validation.constraints.NotNull
            @jakarta.validation.constraints.Positive BigDecimal valor,
            @jakarta.validation.constraints.Size(max = 40) String bandeira,
            @jakarta.validation.constraints.Positive Integer parcelas,
            @jakarta.validation.constraints.Size(max = 40) String nsu,
            @jakarta.validation.constraints.Size(max = 40) String autorizacao) {

        DadosDoPagamento paraDados() {
            return new DadosDoPagamento(forma, new Money(valor), bandeira, parcelas, nsu,
                    autorizacao);
        }
    }

    /**
     * Registra uma forma de pagamento. Várias por comanda — split é requisito (R-UX-08).
     *
     * <p>A resposta traz o <strong>restante</strong>, que é o número que a tela mostra o tempo
     * todo: subtrair no front seria a mesma conta em dois lugares.
     */
    @PostMapping("/{id}/pagamentos")
    public ResponseEntity<ContaDaComanda> registrarPagamento(
            @PathVariable UUID id, @Valid @RequestBody PagamentoRequest req) {
        ContaDaComanda conta = registrarPagamento.executar(id, req.paraDados());
        return ResponseEntity.created(URI.create("/api/v1/comandas/" + id + "/conta"))
                .body(conta);
    }

    @DeleteMapping("/{id}/pagamentos/{pagamentoId}")
    public ContaDaComanda removerPagamento(@PathVariable UUID id,
                                           @PathVariable UUID pagamentoId) {
        return removerPagamento.executar(id, pagamentoId);
    }

    /** Quanto já entrou e quanto falta. É a consulta da tela de fechamento. */
    @GetMapping("/{id}/conta")
    public ResponseEntity<ContaDaComanda> conta(@PathVariable UUID id) {
        return ResponseEntity.of(consultarConta.executar(id));
    }
}
