package br.com.salao.financeiro.internal.web;

import br.com.salao.financeiro.internal.application.Conciliador;
import br.com.salao.financeiro.internal.application.ConciliarUseCase;
import br.com.salao.financeiro.internal.application.ConsultarConciliacaoUseCase;
import br.com.salao.financeiro.internal.application.ConsultarConciliacaoUseCase.Item;
import br.com.salao.financeiro.internal.application.ConsultarConciliacaoUseCase.Recorte;
import br.com.salao.financeiro.internal.application.ConsultarConciliacaoUseCase.Resumo;
import br.com.salao.shared.paginacao.Pagina;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-FIN-010 — a conferência entre o que o salão registrou e o que a maquininha diz.
 *
 * <p>Filtro por data e recorte, nada de dado pessoal na URL (docs/05).
 */
@RestController
@RequestMapping("/api/v1/conciliacao")
public class ConciliacaoController {

    private final ConciliarUseCase conciliar;
    private final ConsultarConciliacaoUseCase consultar;

    public ConciliacaoController(ConciliarUseCase conciliar, ConsultarConciliacaoUseCase consultar) {
        this.conciliar = conciliar;
        this.consultar = consultar;
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
}
