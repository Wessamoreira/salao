package br.com.salao.estoque.internal.application;

import br.com.salao.estoque.api.ErrosDoEstoque;
import br.com.salao.estoque.api.InventarioDeEstoque;
import br.com.salao.estoque.api.StatusDoInventario;
import br.com.salao.estoque.api.TipoDeMovimento;
import br.com.salao.estoque.internal.infra.InventariosJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EST-006 — transformar as divergências em movimento.
 *
 * <h2>O ajuste é um delta</h2>
 *
 * <p>{@code contado − esperado}, com o esperado congelado no instante de cada contagem. Não é
 * "passe a valer o contado": movimento legítimo depois da contagem continua valendo em cima da
 * correção. Ver {@link ContarLoteUseCase}.
 *
 * <h2>Linha que bateu não vira movimento</h2>
 *
 * <p>Um movimento de zero não move saldo nenhum e polui o histórico de todo lote conferido. É a
 * mesma decisão da comissão de zero (RT-FIN-003), e o banco também recusaria
 * ({@code quantidade <> 0}).
 *
 * <h2>Tudo ou nada</h2>
 *
 * <p>Uma transação. Se um lote não aceitar o ajuste — porque vendas depois da contagem
 * consumiram o que faltava —, o inventário inteiro é recusado e nada é escrito. Aplicar
 * parcialmente deixaria o estoque corrigido pela metade, e ninguém saberia qual metade.
 *
 * <h2>O motivo é o próprio inventário</h2>
 *
 * <p>RN-EST-014 exige motivo em todo {@code AJUSTE}, e aqui ele é mais útil do que texto livre por
 * linha: "Inventário de 12/09/2026" amarra o movimento ao documento que o explica, e o documento
 * tem a contagem, o esperado e quem contou.
 */
public class AplicarInventarioUseCase {

    private static final Logger log = LoggerFactory.getLogger(AplicarInventarioUseCase.class);

    private static final DateTimeFormatter DIA =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", new Locale("pt", "BR"));

    private final InventariosJdbc inventarios;
    private final MovimentoComMotivo movimentos;
    private final EstabelecimentoApi estabelecimentos;
    private final AuditoriaApi auditoria;
    private final Relogio relogio;

    public AplicarInventarioUseCase(InventariosJdbc inventarios, MovimentoComMotivo movimentos,
                                    EstabelecimentoApi estabelecimentos, AuditoriaApi auditoria,
                                    Relogio relogio) {
        this.inventarios = inventarios;
        this.movimentos = movimentos;
        this.estabelecimentos = estabelecimentos;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    /** @return quantos movimentos de ajuste foram escritos */
    @PreAuthorize("hasAuthority('" + Permissao.ESTOQUE_ESCREVER + "')")
    @Transactional
    public int executar(UUID inventarioId) {
        UUID tenant = TenantContext.obrigatorio();
        inventarios.travar(inventarioId);

        InventarioDeEstoque inventario = inventarios.porId(inventarioId)
                .orElseThrow(() -> new ErroDeDominio(ErrosDoEstoque.INVENTARIO_NAO_ENCONTRADO,
                        "inventário " + inventarioId + " não encontrado"));
        if (inventario.status() != StatusDoInventario.ABERTO) {
            throw new ErroDeDominio(ErrosDoEstoque.INVENTARIO_ENCERRADO,
                    "este inventário está " + inventario.status().name().toLowerCase());
        }

        String motivo = "Inventário de " + DIA.format(
                inventario.criadoEm().atZone(estabelecimentos.fusoDe(tenant)));
        int escritos = 0;

        for (InventarioDeEstoque.ItemContado item : inventario.itens()) {
            if (item.bateu()) {
                continue;
            }
            String porQue = item.observacao() == null || item.observacao().isBlank()
                    ? motivo
                    : motivo + " — " + item.observacao().strip();
            try {
                movimentos.noLote(item.loteId(), TipoDeMovimento.AJUSTE, item.divergencia(),
                        porQue);
            } catch (ErroDeDominio e) {
                // A recusa genérica diria só "saldo insuficiente". Quem está no depósito precisa
                // saber QUAL produto recontar — com dez lotes conferidos, a mensagem sem nome é
                // inútil.
                throw new ErroDeDominio(ErrosDoEstoque.SALDO_INSUFICIENTE,
                        "o ajuste de " + item.produto() + " (lote " + item.loteCodigo()
                                + ") não cabe: " + e.getMessage()
                                + ". Houve movimento depois da contagem — reconte este lote.");
            }
            escritos++;
        }

        // `where status = 'ABERTO'` no SQL: duas requisições simultâneas de aplicar passariam as
        // duas pela conferência lá em cima, e o lock já as serializa — mas a condição no update é
        // o que garante, e não a ordem em que o Spring resolveu.
        inventarios.encerrar(inventarioId, StatusDoInventario.APLICADO, relogio.agora(),
                UsuarioAtual.idOuNulo());

        auditoria.registrar(RegistroDeAuditoria.alteracao("INVENTARIO_APLICADO", "inventario",
                inventarioId,
                Map.of("itensContados", String.valueOf(inventario.itens().size()),
                        "divergencias", String.valueOf(inventario.quantasDivergem())),
                Map.of("ajustes", String.valueOf(escritos),
                        "saldoDasDivergencias",
                        inventario.saldoDasDivergencias().stripTrailingZeros().toPlainString())));

        log.warn("Inventário {} aplicado: {} de {} linhas divergiram, saldo {}", inventarioId,
                inventario.quantasDivergem(), inventario.itens().size(),
                inventario.saldoDasDivergencias().stripTrailingZeros().toPlainString());
        return escritos;
    }
}
