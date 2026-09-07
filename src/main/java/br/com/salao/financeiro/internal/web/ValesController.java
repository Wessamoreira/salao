package br.com.salao.financeiro.internal.web;

import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.equipe.api.ProfissionalResumo;
import br.com.salao.financeiro.api.FormaDoVale;
import br.com.salao.financeiro.api.Vale;
import br.com.salao.financeiro.internal.application.CancelarValeUseCase;
import br.com.salao.financeiro.internal.application.ListarValesUseCase;
import br.com.salao.financeiro.internal.application.NovoVale;
import br.com.salao.financeiro.internal.application.RegistrarValeUseCase;
import br.com.salao.shared.dinheiro.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-FIN-005 — a porta do vale.
 *
 * <p>Registrar e cancelar exigem {@code financeiro:advance} — é dinheiro saindo do caixa, e não a
 * mesma coisa que ler o financeiro. Listar exige as permissões de leitura, porque o vale de cada um
 * é informação da conta de cada um.
 *
 * <p>Não há {@code DELETE}: cancelar um vale é um ato com motivo obrigatório, e um verbo que
 * significa "apagar" seria a promessa errada — o registro fica. É a mesma forma do estorno de
 * comanda (RT-ATD-007).
 */
@RestController
@RequestMapping("/api/v1/vales")
public class ValesController {

    private final RegistrarValeUseCase registrar;
    private final CancelarValeUseCase cancelar;
    private final ListarValesUseCase listar;
    private final ProfissionalApi profissionais;

    public ValesController(RegistrarValeUseCase registrar, CancelarValeUseCase cancelar,
                           ListarValesUseCase listar, ProfissionalApi profissionais) {
        this.registrar = registrar;
        this.cancelar = cancelar;
        this.listar = listar;
        this.profissionais = profissionais;
    }

    /** Sem data: a competência é o dia do salão e não vem de fora (RN-FIN-014). */
    public record ValeRequest(
            @NotNull UUID profissionalId,
            @NotNull @Positive BigDecimal valor,
            @NotNull FormaDoVale forma,
            @Size(max = 500) String motivo) {
    }

    /** O motivo é o que separa correção de desfazimento silencioso. */
    public record MotivoRequest(@NotBlank @Size(max = 500) String motivo) {
    }

    /**
     * @param profissionalNome composto aqui, e não no caso de uso: o nome é do módulo
     *                         {@code equipe}, e a borda é quem junta — mesma forma da resposta do
     *                         agendamento, que também traz o nome do cliente
     */
    public record ValeResponse(
            UUID id,
            UUID profissionalId,
            String profissionalNome,
            Money valor,
            FormaDoVale forma,
            LocalDate dataCompetencia,
            String motivo,
            boolean cancelado) {
    }

    @PostMapping
    public ResponseEntity<ValeResponse> registrar(
            @Valid @RequestBody ValeRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false)
            String chaveIdempotencia) {
        Vale vale = registrar.executar(new NovoVale(req.profissionalId(),
                new Money(req.valor()), req.forma(), req.motivo(), chaveIdempotencia));
        return ResponseEntity.created(URI.create("/api/v1/vales/" + vale.id()))
                .body(comNome(vale));
    }

    /**
     * Cancela: linha nova de sinal contrário no razão. O vale continua visível, marcado.
     *
     * <p>Repetir devolve o mesmo estado, sem erro — quem repete quer o mesmo resultado final.
     */
    @PostMapping("/{id}/cancelamento")
    public ValeResponse cancelar(@PathVariable UUID id, @Valid @RequestBody MotivoRequest req) {
        return comNome(cancelar.executar(id, req.motivo()));
    }

    /** Sem {@code profissionalId}, os vales do salão inteiro. O período é obrigatório. */
    @GetMapping
    public List<ValeResponse> listar(
            @RequestParam(required = false) UUID profissionalId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate) {
        List<Vale> encontrados = listar.executar(profissionalId, de, ate);
        // Um mapa e não uma consulta por linha: o salão tem dezenas de vales por mês e uma dúzia
        // de profissionais, e resolver nome dentro do laço seria N consultas para M respostas.
        Map<UUID, String> nomes = profissionais.ativos().stream()
                .collect(Collectors.toMap(ProfissionalResumo::id, ProfissionalResumo::comoChamar,
                        (a, b) -> a));
        return encontrados.stream()
                // `containsKey` e não `getOrDefault`: o segundo avalia o padrão sempre, e o padrão
                // aqui é uma consulta — seriam N consultas justamente no caminho que o mapa existe
                // para evitar.
                .map(v -> resposta(v, nomes.containsKey(v.profissionalId())
                        ? nomes.get(v.profissionalId())
                        : nomeDe(v.profissionalId())))
                .toList();
    }

    private ValeResponse comNome(Vale vale) {
        return resposta(vale, nomeDe(vale.profissionalId()));
    }

    /**
     * Quem saiu da equipe ainda tem vale no histórico, e {@code ativos()} não o traz — sem esta
     * busca, a lista do mês passado apareceria com nomes em branco.
     */
    private String nomeDe(UUID profissionalId) {
        return profissionais.porId(profissionalId)
                .map(ProfissionalResumo::comoChamar)
                .orElse(null);
    }

    private ValeResponse resposta(Vale vale, String nome) {
        return new ValeResponse(vale.id(), vale.profissionalId(), nome, vale.valor(),
                vale.forma(), vale.dataCompetencia(), vale.motivo(), vale.cancelado());
    }
}
