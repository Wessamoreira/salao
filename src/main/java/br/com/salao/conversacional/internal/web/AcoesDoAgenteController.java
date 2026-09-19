package br.com.salao.conversacional.internal.web;

import br.com.salao.conversacional.internal.application.ConsultarAcoesDoAgenteUseCase;
import br.com.salao.conversacional.internal.application.ConsultarAcoesDoAgenteUseCase.Acao;
import br.com.salao.conversacional.internal.application.ConsultarAcoesDoAgenteUseCase.Filtro;
import br.com.salao.conversacional.internal.application.ConsultarAcoesDoAgenteUseCase.Pessoa;
import br.com.salao.conversacional.internal.application.ConsultarAcoesDoAgenteUseCase.Recorte;
import br.com.salao.conversacional.internal.application.ConsultarAcoesDoAgenteUseCase.Resumo;
import br.com.salao.shared.paginacao.Pagina;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-CNV-007 — o painel de revisão do agente. Só leitura, com {@code agente:review}.
 *
 * <p>Filtro nos parâmetros de consulta, e nenhum deles é dado pessoal: pessoa é id, período é data.
 * O termo de uma busca por texto não entra — nunca dado sensível em URL (docs/05).
 */
@RestController
@RequestMapping("/api/v1/agente/acoes")
public class AcoesDoAgenteController {

    private final ConsultarAcoesDoAgenteUseCase consultar;

    public AcoesDoAgenteController(ConsultarAcoesDoAgenteUseCase consultar) {
        this.consultar = consultar;
    }

    @GetMapping
    public Pagina<Acao> listar(
            @RequestParam(defaultValue = "TODAS") Recorte recorte,
            @RequestParam(required = false) UUID pessoa,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limite) {
        return consultar.listar(new Filtro(recorte, pessoa, de, ate), cursor, limite);
    }

    @GetMapping("/resumo")
    public Resumo resumo(
            @RequestParam(required = false) UUID pessoa,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate) {
        return consultar.resumir(new Filtro(Recorte.TODAS, pessoa, de, ate));
    }

    @GetMapping("/pessoas")
    public List<Pessoa> pessoas(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate) {
        return consultar.pessoas(new Filtro(Recorte.TODAS, null, de, ate));
    }
}
