package br.com.salao.financeiro.internal.web;

import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.equipe.api.ProfissionalResumo;
import br.com.salao.financeiro.api.FechamentoDoProfissional;
import br.com.salao.financeiro.api.PreviaDoFechamento;
import br.com.salao.financeiro.internal.application.EstornarFechamentoUseCase;
import br.com.salao.financeiro.internal.application.FecharPeriodoUseCase;
import br.com.salao.financeiro.internal.application.ListarFechamentosUseCase;
import br.com.salao.financeiro.internal.application.PreverFechamentoUseCase;
import br.com.salao.shared.dinheiro.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-FIN-006 — a porta do fechamento.
 *
 * <p>A prévia é rota separada e {@code GET} de propósito: ela não grava, e a tela precisa poder
 * chamá-la à vontade enquanto o dono ajusta as datas. Fechar é {@code POST} num recurso, porque é
 * um ato — não a edição de um campo de status.
 *
 * <p>O período vai no corpo, e não em query string, pela mesma razão de sempre neste projeto: é o
 * dado do formulário, e costurá-lo em dois lugares convida o front a divergir de si mesmo.
 */
@RestController
@RequestMapping("/api/v1/fechamentos")
public class FechamentosController {

    private final PreverFechamentoUseCase prever;
    private final FecharPeriodoUseCase fechar;
    private final EstornarFechamentoUseCase estornar;
    private final ListarFechamentosUseCase listar;
    private final ProfissionalApi profissionais;

    public FechamentosController(PreverFechamentoUseCase prever, FecharPeriodoUseCase fechar,
                                 EstornarFechamentoUseCase estornar,
                                 ListarFechamentosUseCase listar, ProfissionalApi profissionais) {
        this.prever = prever;
        this.fechar = fechar;
        this.estornar = estornar;
        this.listar = listar;
        this.profissionais = profissionais;
    }

    public record PeriodoRequest(
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @NotBlank @Size(min = 64, max = 64) String versaoDaPrevia) {
    }

    /** O nome é composto na borda: ele é do módulo {@code equipe}. */
    public record PreviaResponse(
            LocalDate de,
            LocalDate ate,
            BigDecimal totalARepassar,
            int quantosRecebem,
            /** O hash das posições: é ele que garante que se confirma o número que se viu. */
            String versao,
            List<LinhaResponse> linhas) {

        public record LinhaResponse(
                UUID profissionalId,
                String profissionalNome,
                BigDecimal saldoAnterior,
                BigDecimal movimentoDoPeriodo,
                /** Antes do piso: negativo quando é a pessoa que deve ao salão. */
                BigDecimal saldoFinal,
                BigDecimal aRepassar) {

            static LinhaResponse de(PreviaDoFechamento.LinhaDaPrevia l, String nome) {
                return new LinhaResponse(l.profissionalId(), nome, l.saldoAnterior().valor(),
                        l.movimentoDoPeriodo().valor(), l.saldoFinal().valor(),
                        l.aRepassar().valor());
            }
        }

        static PreviaResponse de(PreviaDoFechamento previa, Map<UUID, String> nomes) {
            return new PreviaResponse(previa.de(), previa.ate(),
                    previa.totalARepassar().valor(), previa.quantosRecebem(), previa.versao(),
                    previa.linhas().stream()
                            .map(l -> LinhaResponse.de(l, nomes.get(l.profissionalId())))
                            .toList());
        }
    }

    public record FechamentoResponse(
            UUID id,
            UUID profissionalId,
            String profissionalNome,
            LocalDate periodoInicio,
            LocalDate periodoFim,
            BigDecimal saldoAnterior,
            BigDecimal movimentoDoPeriodo,
            BigDecimal valorRepassado,
            BigDecimal saldoATransportar,
            /** Nulo enquanto o documento vale. Preenchido, ele é histórico. */
            java.time.Instant estornadoEm) {

        static FechamentoResponse de(FechamentoDoProfissional f, String nome) {
            return new FechamentoResponse(f.id(), f.profissionalId(), nome, f.periodoInicio(),
                    f.periodoFim(), f.saldoAnterior().valor(), f.movimentoDoPeriodo().valor(),
                    f.valorRepassado().valor(), f.saldoATransportar().valor(), f.estornadoEm());
        }
    }

    /**
     * <em>"Isso vai gerar R$ 3.480,00 de repasse para 4 profissionais"</em> — R-UX-05.
     *
     * <p>Recusa exatamente o que o fechamento recusaria: prévia que aceita o que a confirmação
     * nega promete um número e entrega um erro.
     */
    @GetMapping("/previa")
    public PreviaResponse previa(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate) {
        PreviaDoFechamento previa = prever.executar(de, ate);
        Map<UUID, String> nomes = nomesDe(previa.linhas().stream()
                .map(PreviaDoFechamento.LinhaDaPrevia::profissionalId).toList());
        return PreviaResponse.de(previa, nomes);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public List<FechamentoResponse> fechar(@Valid @RequestBody PeriodoRequest req) {
        return comNomes(fechar.executar(req.de(), req.ate(), req.versaoDaPrevia()));
    }

    /**
     * Desfaz um fechamento: anula o repasse e devolve o período.
     *
     * <p>{@code POST} num sub-recurso, e não {@code DELETE}: o documento fica. Ele passa a contar
     * que em tal dia pagou-se e depois anulou-se — que é justamente o que explica o dinheiro que
     * saiu da conta.
     *
     * <p>Repetir devolve o mesmo documento, sem sobrescrever a data do primeiro estorno.
     */
    @PostMapping("/{id}/estorno")
    public FechamentoResponse estornar(@PathVariable UUID id,
                                       @Valid @RequestBody MotivoRequest req) {
        return comNomes(List.of(estornar.executar(id, req.motivo()))).getFirst();
    }

    /** O motivo é o que separa correção de desfazimento silencioso. */
    public record MotivoRequest(@NotBlank @Size(max = 500) String motivo) {
    }

    /** Sem {@code profissionalId}, os contracheques de todo mundo no intervalo. */
    @GetMapping
    public List<FechamentoResponse> listar(
            @RequestParam(required = false) UUID profissionalId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate) {
        return comNomes(listar.executar(profissionalId, de, ate));
    }

    private List<FechamentoResponse> comNomes(List<FechamentoDoProfissional> fechamentos) {
        Map<UUID, String> nomes = nomesDe(fechamentos.stream()
                .map(FechamentoDoProfissional::profissionalId).toList());
        return fechamentos.stream()
                .map(f -> FechamentoResponse.de(f, nomes.get(f.profissionalId())))
                .toList();
    }

    /**
     * Os nomes de quem aparece na resposta, numa consulta só.
     *
     * <p>Parte dos ativos porque é uma chamada para a equipe inteira, e completa com busca
     * individual apenas para quem saiu — um contracheque de março continua sendo de alguém, e sem
     * isso a lista do mês passado viria com nomes em branco.
     */
    private Map<UUID, String> nomesDe(List<UUID> ids) {
        Map<UUID, String> nomes = profissionais.ativos().stream()
                .collect(Collectors.toMap(ProfissionalResumo::id, ProfissionalResumo::comoChamar,
                        (a, b) -> a));
        ids.stream()
                .filter(Predicate.not(nomes::containsKey))
                .distinct()
                .forEach(id -> profissionais.porId(id)
                        .ifPresent(p -> nomes.put(id, p.comoChamar())));
        return nomes;
    }
}
