package br.com.salao.financeiro.internal.web;

import br.com.salao.financeiro.api.FormaDePagamento;
import br.com.salao.financeiro.internal.application.Conciliador;
import br.com.salao.financeiro.internal.application.ConciliarUseCase;
import br.com.salao.financeiro.internal.application.ConsultarConciliacaoUseCase;
import br.com.salao.financeiro.internal.application.ConsultarConciliacaoUseCase.Item;
import br.com.salao.financeiro.internal.application.ConsultarConciliacaoUseCase.ItemResolvido;
import br.com.salao.financeiro.internal.application.ConsultarConciliacaoUseCase.Recorte;
import br.com.salao.financeiro.internal.application.ConsultarConciliacaoUseCase.Resumo;
import br.com.salao.financeiro.internal.application.ResolverDivergenciaUseCase;
import br.com.salao.financeiro.internal.domain.AcaoDaResolucao;
import br.com.salao.financeiro.internal.domain.AlvoDaResolucao;
import br.com.salao.shared.paginacao.Pagina;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-FIN-010 — a conferência entre o que o salão registrou e o que a maquininha diz.
 * RT-FIN-011 — e a decisão sobre cada linha dela, com motivo e trilha.
 *
 * <p>Filtro por data e recorte, nada de dado pessoal na URL (docs/05).
 */
@RestController
@RequestMapping("/api/v1/conciliacao")
public class ConciliacaoController {

    private final ConciliarUseCase conciliar;
    private final ConsultarConciliacaoUseCase consultar;
    private final ResolverDivergenciaUseCase resolver;

    public ConciliacaoController(ConciliarUseCase conciliar, ConsultarConciliacaoUseCase consultar,
                                 ResolverDivergenciaUseCase resolver) {
        this.conciliar = conciliar;
        this.consultar = consultar;
        this.resolver = resolver;
    }

    /** "Conciliar agora": casa o que ficou sem par desde a última importação. */
    @PostMapping("/casamentos")
    public Conciliador.Rodada conciliar() {
        return conciliar.executar();
    }

    @GetMapping("/resumo")
    public Resumo resumo(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate) {
        return consultar.resumir(de, ate);
    }

    @GetMapping("/itens")
    public Pagina<Item> itens(
            @RequestParam(defaultValue = "DIVERGENTES") Recorte recorte,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limite) {
        return consultar.listar(recorte, de, ate, cursor, limite);
    }

    // ------------------------------------------------------------------ RT-FIN-011 — resolver

    /**
     * O que foi decidido sobre uma linha da conferência.
     *
     * @param forma só com {@code alvo = PAGAMENTO}: a forma verdadeira, que não é de cartão
     */
    public record PedidoDeResolucao(@NotNull AlvoDaResolucao alvo, @NotNull UUID id,
                                    @NotNull AcaoDaResolucao acao,
                                    @NotBlank @Size(max = 500) String motivo,
                                    FormaDePagamento forma) {
    }

    @PostMapping("/resolucoes")
    public ResolverDivergenciaUseCase.Resultado resolver(
            @Valid @RequestBody PedidoDeResolucao pedido) {
        return resolver.executar(new ResolverDivergenciaUseCase.Comando(pedido.alvo(), pedido.id(),
                pedido.acao(), pedido.motivo(), pedido.forma()));
    }

    /** A trilha do período: o que saiu da conferência por decisão de alguém, e por quê. */
    @GetMapping("/resolucoes")
    public Pagina<ItemResolvido> resolucoes(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limite) {
        return consultar.listarResolvidas(de, ate, cursor, limite);
    }

    public record PedidoDeReabertura(@NotBlank @Size(max = 500) String motivo) {
    }

    /**
     * Devolve a linha à conferência, desfazendo o que a resolução fez.
     *
     * <p>{@code POST} num sub-recurso, e não {@code DELETE}: a resolução não é apagada — a role não tem
     * {@code delete} nesta tabela (V60), e o verbo precisa prometer o que acontece. É a mesma forma do
     * estorno de lançamento (RT-FIN-007).
     */
    @PostMapping("/resolucoes/{id}/reabertura")
    public void reabrir(@PathVariable UUID id, @Valid @RequestBody PedidoDeReabertura pedido) {
        resolver.reabrir(id, pedido.motivo());
    }
}
