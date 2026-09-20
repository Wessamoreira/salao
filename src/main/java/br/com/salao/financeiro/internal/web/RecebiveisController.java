package br.com.salao.financeiro.internal.web;

import br.com.salao.financeiro.internal.application.PreverRecebiveisUseCase;
import br.com.salao.financeiro.internal.application.PreverRecebiveisUseCase.Previsao;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** RT-FIN-012 — quanto o salão tem a receber da adquirente, e em que dia. */
@RestController
@RequestMapping("/api/v1/recebiveis")
public class RecebiveisController {

    private final PreverRecebiveisUseCase prever;

    public RecebiveisController(PreverRecebiveisUseCase prever) {
        this.prever = prever;
    }

    @GetMapping
    public Previsao previsao(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate) {
        return prever.executar(de, ate);
    }
}
