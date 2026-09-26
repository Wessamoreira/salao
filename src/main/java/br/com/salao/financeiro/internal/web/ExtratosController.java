package br.com.salao.financeiro.internal.web;

import br.com.salao.financeiro.api.ExtratoDoProfissional;
import br.com.salao.financeiro.internal.application.ConsultarExtratoUseCase;
import br.com.salao.financeiro.api.TipoDeLancamento;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

    /**
     * O extrato como a tela o recebe.
     *
     * <p>{@code Money} serializa como {@code {"valor": …}} e {@code saldoFinal()} é método
     * derivado, que o Jackson não vê — método de record não vira campo. A tela recalcular
     * "anterior + a receber" seria barato e errado: é a segunda cópia de uma conta que já tem
     * dono.
     */
    public record ExtratoResponse(
            UUID profissionalId,
            String nome,
            LocalDate de,
            LocalDate ate,
            BigDecimal saldoAnterior,
            BigDecimal aReceber,
            BigDecimal saldoFinal,
            List<TotalResponse> totais,
            List<LinhaResponse> linhas) {

        static ExtratoResponse de(ExtratoDoProfissional e) {
            return new ExtratoResponse(e.profissionalId(), e.nome(), e.de(), e.ate(),
                    e.saldoAnterior().valor(), e.aReceber().valor(), e.saldoFinal().valor(),
                    e.totais().stream().map(TotalResponse::de).toList(),
                    e.linhas().stream().map(LinhaResponse::de).toList());
        }
    }

    public record TotalResponse(TipoDeLancamento tipo, BigDecimal valor) {

        static TotalResponse de(ExtratoDoProfissional.TotalPorTipo t) {
            return new TotalResponse(t.tipo(), t.valor().valor());
        }
    }

    /** @param valor já invertido pelo caso de uso: comissão positiva, vale negativo */
    public record LinhaResponse(
            LocalDate data,
            TipoDeLancamento tipo,
            String descricao,
            BigDecimal valor,
            String origemTipo,
            UUID origemId,
            /** O id da própria linha do razão: é por ele que se estorna (RT-FIN-007). */
            UUID lancamentoId) {

        static LinhaResponse de(ExtratoDoProfissional.LinhaDoExtrato l) {
            return new LinhaResponse(l.data(), l.tipo(), l.descricao(), l.valor().valor(),
                    l.origemTipo(), l.origemId(), l.lancamentoId());
        }
    }

    @GetMapping("/{profissionalId}")
    public ExtratoResponse doProfissional(
            @PathVariable UUID profissionalId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate) {
        return ExtratoResponse.de(consultar.executar(profissionalId, de, ate));
    }
}
