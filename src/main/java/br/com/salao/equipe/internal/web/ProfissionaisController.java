package br.com.salao.equipe.internal.web;

import br.com.salao.equipe.api.IntervaloDeTrabalho;
import br.com.salao.equipe.api.JornadaSemanal;
import br.com.salao.equipe.api.ProfissionalResumo;
import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.equipe.api.TipoDeVinculo;
import br.com.salao.equipe.internal.application.CadastrarProfissionalUseCase;
import br.com.salao.equipe.internal.application.DadosDoProfissional;
import br.com.salao.equipe.internal.application.DefinirAtivacaoDoProfissionalUseCase;
import br.com.salao.equipe.internal.application.DefinirJornadaUseCase;
import br.com.salao.equipe.internal.application.PeriodoBruto;
import br.com.salao.equipe.internal.application.ListarProfissionaisUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-EQP-001 — cadastro de profissional.
 *
 * <p>Transporte apenas. A autorização vive nos casos de uso — é o que faz a regra valer também
 * para o bot da Fase 4, que não passa por aqui.
 */
@RestController
@RequestMapping("/api/v1/profissionais")
public class ProfissionaisController {

    private final CadastrarProfissionalUseCase cadastrar;
    private final ListarProfissionaisUseCase listar;
    private final DefinirAtivacaoDoProfissionalUseCase definirAtivacao;
    private final DefinirJornadaUseCase definirJornada;
    private final ProfissionalApi equipe;

    public ProfissionaisController(CadastrarProfissionalUseCase cadastrar,
                                   ListarProfissionaisUseCase listar,
                                   DefinirAtivacaoDoProfissionalUseCase definirAtivacao,
                                   DefinirJornadaUseCase definirJornada,
                                   ProfissionalApi equipe) {
        this.cadastrar = cadastrar;
        this.listar = listar;
        this.definirAtivacao = definirAtivacao;
        this.definirJornada = definirJornada;
        this.equipe = equipe;
    }

    /**
     * Sem {@code @Pattern} no documento: ele chega com máscara, e quem sabe o que é um CPF válido
     * é o domínio, que também sabe conferir o dígito verificador.
     */
    public record CadastrarProfissionalRequest(
            @NotBlank @Size(max = 200) String nome,
            @Size(max = 60) String apelido,
            @NotBlank @Size(max = 20) String documento,
            @NotNull TipoDeVinculo vinculo,
            @Size(max = 30) String telefone,
            @Email @Size(max = 320) String email,
            BigDecimal comissaoPercentual,
            LocalDate contratoInicio,
            LocalDate contratoFim,
            LocalDate contratoAssinadoEm) {
    }

    public record AtivacaoRequest(@NotNull Boolean ativo) {
    }

    @GetMapping
    public List<ProfissionalResumo> listar() {
        return listar.executar();
    }

    @PostMapping
    public ResponseEntity<ProfissionalResumo> cadastrar(
            @Valid @RequestBody CadastrarProfissionalRequest req) {
        ProfissionalResumo criado = cadastrar.executar(new DadosDoProfissional(req.nome(),
                req.apelido(), req.documento(), req.vinculo(), req.telefone(), req.email(),
                req.comissaoPercentual(), req.contratoInicio(), req.contratoFim(),
                req.contratoAssinadoEm()));
        return ResponseEntity.created(URI.create("/api/v1/profissionais/" + criado.id()))
                .body(criado);
    }

    /**
     * RT-EQP-002 — a jornada, com os dias em ISO ({@code 1} = segunda … {@code 7} = domingo).
     *
     * <p>O mesmo esquema do {@code java.time.DayOfWeek} e do {@code isodow} do Postgres: escolher
     * outro obrigaria a converter em toda fronteira, e é aí que erro de um dia aparece.
     */
    public record IntervaloRequest(@NotNull LocalTime inicio, @NotNull LocalTime fim) {
    }

    @GetMapping("/{id}/jornada")
    public Map<Integer, List<IntervaloDeTrabalho>> jornada(@PathVariable UUID id) {
        return emIso(equipe.jornadaDe(id));
    }

    /**
     * Substitui a semana inteira.
     *
     * <p>{@code PUT} porque é isso que acontece: aplicar diferenças existiria só para economizar
     * dez escritas e erraria na primeira jornada com dois intervalos no mesmo dia.
     */
    @PutMapping("/{id}/jornada")
    public Map<Integer, List<IntervaloDeTrabalho>> definirJornada(
            @PathVariable UUID id,
            @Valid @RequestBody Map<Integer, List<IntervaloRequest>> semana) {
        // O controller NÃO constrói objeto de domínio: o construtor de IntervaloDeTrabalho
        // recusa intervalo invertido com IllegalArgumentException, e ela viraria 500 aqui.
        var porDia = new java.util.EnumMap<DayOfWeek, List<PeriodoBruto>>(DayOfWeek.class);
        semana.forEach((dia, intervalos) -> porDia.put(DayOfWeek.of(dia),
                intervalos.stream().map(i -> new PeriodoBruto(i.inicio(), i.fim())).toList()));
        return emIso(definirJornada.executar(id, porDia));
    }

    private Map<Integer, List<IntervaloDeTrabalho>> emIso(JornadaSemanal jornada) {
        var saida = new java.util.TreeMap<Integer, List<IntervaloDeTrabalho>>();
        jornada.porDia().forEach((dia, intervalos) -> saida.put(dia.getValue(), intervalos));
        return saida;
    }

    @PatchMapping("/{id}/ativacao")
    public ProfissionalResumo definirAtivacao(@PathVariable UUID id,
                                              @Valid @RequestBody AtivacaoRequest req) {
        return definirAtivacao.executar(id, req.ativo());
    }
}
