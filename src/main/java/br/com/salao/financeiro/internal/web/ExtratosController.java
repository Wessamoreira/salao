package br.com.salao.financeiro.internal.web;

import br.com.salao.financeiro.api.ExtratoDoProfissional;
import br.com.salao.financeiro.internal.application.ConsultarExtratoUseCase;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-FIN-004 — a porta do extrato.
 *
 * <p>O período é obrigatório e vem de quem pergunta. Um padrão de "mês corrente" pareceria
 * conveniente e seria a fonte da conversa mais cara deste sistema: duas pessoas olhando telas com
 * períodos diferentes e concluindo que uma delas está errada.
 */
@RestController
@RequestMapping("/api/v1/extratos")
public class ExtratosController {

    private final ConsultarExtratoUseCase consultar;

    public ExtratosController(ConsultarExtratoUseCase consultar) {
        this.consultar = consultar;
    }

    @GetMapping("/{profissionalId}")
    public ExtratoDoProfissional doProfissional(
            @PathVariable UUID profissionalId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate) {
        return consultar.executar(profissionalId, de, ate);
    }
}
