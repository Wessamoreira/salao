package br.com.salao.estoque.internal.application;

import br.com.salao.estoque.api.ErrosDoEstoque;
import br.com.salao.estoque.api.StatusDoInventario;
import br.com.salao.estoque.internal.infra.InventariosJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tempo.Relogio;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EST-006 — desistir da contagem.
 *
 * <p>Nada é escrito no estoque, e as contagens ficam: um inventário cancelado é o registro de que
 * alguém contou e o resultado não foi aplicado, que é informação — especialmente se a contagem for
 * refeita e der outro número.
 *
 * <p>Cancelar é o que libera o salão para abrir outro (o índice único da V46 só alcança o aberto).
 * É por isso que a operação existe em vez de simplesmente abandonar a contagem.
 */
public class CancelarInventarioUseCase {

    private static final Logger log = LoggerFactory.getLogger(CancelarInventarioUseCase.class);

    private final InventariosJdbc inventarios;
    private final AuditoriaApi auditoria;
    private final Relogio relogio;

    public CancelarInventarioUseCase(InventariosJdbc inventarios, AuditoriaApi auditoria,
                                     Relogio relogio) {
        this.inventarios = inventarios;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    @PreAuthorize("hasAuthority('" + Permissao.ESTOQUE_ESCREVER + "')")
    @Transactional
    public void executar(UUID inventarioId, String motivo) {
        if (motivo == null || motivo.isBlank()) {
            throw new ErroDeDominio(ErrosDoEstoque.MOTIVO_OBRIGATORIO,
                    "informe por que a contagem foi descartada");
        }
        StatusDoInventario status = inventarios.statusDe(inventarioId)
                .orElseThrow(() -> new ErroDeDominio(ErrosDoEstoque.INVENTARIO_NAO_ENCONTRADO,
                        "inventário " + inventarioId + " não encontrado"));
        // Idempotente: já cancelado devolve sem reclamar. Já APLICADO, não — desfazer um ajuste
        // aplicado é outro ato, e ele tem caminho próprio (RT-EST-005, ajuste contrário).
        if (status == StatusDoInventario.CANCELADO) {
            return;
        }
        if (status == StatusDoInventario.APLICADO) {
            throw new ErroDeDominio(ErrosDoEstoque.INVENTARIO_ENCERRADO,
                    "este inventário já foi aplicado; para desfazer, lance o ajuste contrário");
        }

        inventarios.encerrar(inventarioId, StatusDoInventario.CANCELADO, relogio.agora(),
                UsuarioAtual.idOuNulo());
        auditoria.registrar(RegistroDeAuditoria.alteracao("INVENTARIO_CANCELADO", "inventario",
                inventarioId, Map.of("status", status.name()), Map.of("motivo", motivo.strip())));
        log.warn("Inventário {} cancelado: {}", inventarioId, motivo.strip());
    }
}
