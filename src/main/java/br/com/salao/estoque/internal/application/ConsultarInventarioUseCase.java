package br.com.salao.estoque.internal.application;

import br.com.salao.estoque.api.ErrosDoEstoque;
import br.com.salao.estoque.api.InventarioDeEstoque;
import br.com.salao.estoque.internal.infra.InventariosJdbc;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.erro.ErroDeDominio;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EST-006 — a folha de conferência.
 *
 * <p>É o que a pessoa lê antes de aplicar, e é onde R-UX-05 se cumpre: a confirmação precisa dizer
 * o efeito em número — quantas linhas divergem e qual o saldo delas.
 *
 * <p>Permissão de LEITURA de estoque: conferir a contagem não é mexer nela.
 */
public class ConsultarInventarioUseCase {

    private final InventariosJdbc inventarios;

    public ConsultarInventarioUseCase(InventariosJdbc inventarios) {
        this.inventarios = inventarios;
    }

    @PreAuthorize("hasAuthority('" + Permissao.ESTOQUE_LER + "')")
    @Transactional(readOnly = true)
    public InventarioDeEstoque executar(UUID inventarioId) {
        return inventarios.porId(inventarioId)
                .orElseThrow(() -> new ErroDeDominio(ErrosDoEstoque.INVENTARIO_NAO_ENCONTRADO,
                        "inventário " + inventarioId + " não encontrado"));
    }
}
