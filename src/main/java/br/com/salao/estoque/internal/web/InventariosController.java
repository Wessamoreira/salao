package br.com.salao.estoque.internal.web;

import br.com.salao.estoque.api.InventarioDeEstoque;
import br.com.salao.estoque.api.StatusDoInventario;
import br.com.salao.estoque.internal.application.AbrirInventarioUseCase;
import br.com.salao.estoque.internal.application.AplicarInventarioUseCase;
import br.com.salao.estoque.internal.application.CancelarInventarioUseCase;
import br.com.salao.estoque.internal.application.ConsultarInventarioAbertoUseCase;
import br.com.salao.estoque.internal.application.ConsultarInventarioUseCase;
import br.com.salao.estoque.internal.application.ContarLoteUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-EST-013 — a porta do inventário.
 *
 * <p>Os cinco casos de uso existiam desde a Fase 3, testados, e **nenhum tinha rota**: a própria
 * doc de RT-EST-006 registrava "não há rota nem tela" como a pendência que mais faltava.
 *
 * <p>Transporte apenas: a autorização vive nos casos de uso.
 */
@RestController
@RequestMapping("/api/v1/inventarios")
public class InventariosController {

    private final AbrirInventarioUseCase abrir;
    private final ContarLoteUseCase contar;
    private final ConsultarInventarioUseCase consultar;
    private final ConsultarInventarioAbertoUseCase consultarAberto;
    private final AplicarInventarioUseCase aplicar;
    private final CancelarInventarioUseCase cancelar;

    public InventariosController(AbrirInventarioUseCase abrir, ContarLoteUseCase contar,
                                 ConsultarInventarioUseCase consultar,
                                 ConsultarInventarioAbertoUseCase consultarAberto,
                                 AplicarInventarioUseCase aplicar,
                                 CancelarInventarioUseCase cancelar) {
        this.abrir = abrir;
        this.contar = contar;
        this.consultar = consultar;
        this.consultarAberto = consultarAberto;
        this.aplicar = aplicar;
        this.cancelar = cancelar;
    }

    /**
     * A contagem como a tela a lê.
     *
     * <p>{@code divergencia}, {@code bateu}, {@code quantasDivergem} e {@code saldoDasDivergencias}
     * são métodos derivados do registro de domínio, e o Jackson não os serializa — método de record
     * não vira campo. Deixar a tela recalcular "contada − esperada" seria barato e errado pela
     * mesma razão de sempre: a segunda cópia da conta é a que ninguém corrige.
     */
    public record InventarioResponse(
            UUID id,
            StatusDoInventario status,
            String observacao,
            Instant criadoEm,
            Instant encerradoEm,
            int quantasDivergem,
            BigDecimal saldoDasDivergencias,
            List<ItemResponse> itens) {

        static InventarioResponse de(InventarioDeEstoque i) {
            return new InventarioResponse(i.id(), i.status(), i.observacao(), i.criadoEm(),
                    i.encerradoEm(), i.quantasDivergem(), i.saldoDasDivergencias(),
                    i.itens().stream().map(ItemResponse::de).toList());
        }
    }

    public record ItemResponse(
            UUID loteId,
            UUID produtoId,
            String produto,
            String loteCodigo,
            BigDecimal esperada,
            BigDecimal contada,
            BigDecimal divergencia,
            boolean bateu,
            String observacao,
            Instant contadoEm) {

        static ItemResponse de(InventarioDeEstoque.ItemContado item) {
            return new ItemResponse(item.loteId(), item.produtoId(), item.produto(),
                    item.loteCodigo(), item.esperada(), item.contada(), item.divergencia(),
                    item.bateu(), item.observacao(), item.contadoEm());
        }
    }

    public record AbrirRequest(@Size(max = 500) String observacao) {
    }

    public record ContagemRequest(
            @NotNull @PositiveOrZero BigDecimal contada,
            @Size(max = 500) String observacao) {
    }

    /** Cancelar exige motivo: uma contagem abandonada sem explicação é um buraco na trilha. */
    public record CancelarRequest(String motivo) {
    }

    @PostMapping
    public ResponseEntity<InventarioResponse> abrir(@Valid @RequestBody(required = false)
                                                    AbrirRequest req) {
        UUID id = abrir.executar(req == null ? null : req.observacao());
        return ResponseEntity.created(URI.create("/api/v1/inventarios/" + id))
                .body(InventarioResponse.de(consultar.executar(id)));
    }

    /**
     * RN-EST-040 — a contagem aberta deste salão, sem precisar do id.
     *
     * <p>Caminho literal antes do {@code /{id}}: o Spring prefere o mapeamento mais específico, e
     * "aberto" nunca é convertido para UUID.
     *
     * <p>{@code 204} quando não há nenhuma — não um {@code 404}, que diria "esta rota não existe",
     * nem um envelope {@code {"aberto": null}}, que obrigaria a tela a desembrulhar em toda
     * leitura para descobrir a mesma coisa.
     */
    @GetMapping("/aberto")
    public ResponseEntity<InventarioResponse> aberto() {
        return ResponseEntity.of(consultarAberto.executar().map(InventarioResponse::de));
    }

    @GetMapping("/{id}")
    public InventarioResponse consultar(@PathVariable UUID id) {
        return InventarioResponse.de(consultar.executar(id));
    }

    /**
     * Conta um lote. {@code PUT} porque recontar <strong>substitui</strong> (RN-EST-019): o mesmo
     * pedido, repetido, deixa o mesmo estado — que é o que o verbo promete.
     */
    @PutMapping("/{id}/lotes/{loteId}")
    public InventarioResponse contar(@PathVariable UUID id, @PathVariable UUID loteId,
                                     @Valid @RequestBody ContagemRequest req) {
        contar.executar(id, loteId, req.contada(), req.observacao());
        return InventarioResponse.de(consultar.executar(id));
    }

    /** @return quantas linhas viraram movimento de ajuste */
    @PostMapping("/{id}/aplicar")
    public AplicacaoResponse aplicar(@PathVariable UUID id) {
        return new AplicacaoResponse(aplicar.executar(id));
    }

    public record AplicacaoResponse(int linhasAjustadas) {
    }

    @PostMapping("/{id}/cancelar")
    public ResponseEntity<Void> cancelar(@PathVariable UUID id,
                                         @RequestBody CancelarRequest req) {
        cancelar.executar(id, req == null ? null : req.motivo());
        return ResponseEntity.noContent().build();
    }
}
