package br.com.salao.financeiro.internal.web;

import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.equipe.api.ProfissionalResumo;
import br.com.salao.financeiro.api.FechamentoDoProfissional;
import br.com.salao.financeiro.api.PreviaDoFechamento;
import br.com.salao.financeiro.internal.application.FecharPeriodoUseCase;
import br.com.salao.financeiro.internal.application.ListarFechamentosUseCase;
import br.com.salao.financeiro.internal.application.PreverFechamentoUseCase;
import br.com.salao.shared.dinheiro.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final ListarFechamentosUseCase listar;
    private final ProfissionalApi profissionais;

    public FechamentosController(PreverFechamentoUseCase prever, FecharPeriodoUseCase fechar,
                                 ListarFechamentosUseCase listar, ProfissionalApi profissionais) {
        this.prever = prever;
        this.fechar = fechar;
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
            Money totalARepassar,
            int quantosRecebem,
            String versao,
            List<LinhaResponse> linhas) {

        public record LinhaResponse(
                UUID profissionalId,
                String profissionalNome,
                Money saldoAnterior,
                Money movimentoDoPeriodo,
                Money saldoFinal,
                Money aRepassar) {
        }
    }

    public record FechamentoResponse(
            UUID id,
            UUID profissionalId,
            String profissionalNome,
            LocalDate periodoInicio,
            LocalDate periodoFim,
            Money saldoAnterior,
            Money movimentoDoPeriodo,
            Money valorRepassado,
            Money saldoATransportar) {
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
        return new PreviaResponse(previa.de(), previa.ate(), previa.totalARepassar(),
                previa.quantosRecebem(), previa.versao(),
                previa.linhas().stream()
                        .map(l -> new PreviaResponse.LinhaResponse(l.profissionalId(),
                                nomes.get(l.profissionalId()), l.saldoAnterior(),
                                l.movimentoDoPeriodo(), l.saldoFinal(), l.aRepassar()))
                        .toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public List<FechamentoResponse> fechar(@Valid @RequestBody PeriodoRequest req) {
        return comNomes(fechar.executar(req.de(), req.ate(), req.versaoDaPrevia()));
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
                .map(f -> new FechamentoResponse(f.id(), f.profissionalId(),
                        nomes.get(f.profissionalId()), f.periodoInicio(), f.periodoFim(),
                        f.saldoAnterior(), f.movimentoDoPeriodo(), f.valorRepassado(),
                        f.saldoATransportar()))
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
