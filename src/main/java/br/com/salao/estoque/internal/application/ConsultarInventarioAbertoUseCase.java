package br.com.salao.estoque.internal.application;

import br.com.salao.estoque.api.InventarioDeEstoque;
import br.com.salao.estoque.internal.infra.InventariosJdbc;
import br.com.salao.iam.api.Permissao;
import java.util.Optional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EST-013 / RN-EST-040 — a contagem que ficou aberta.
 *
 * <h2>Por que precisou existir</h2>
 *
 * <p>Um inventário só era alcançável pelo {@code id} que {@code abrir} devolveu, e o banco só
 * admite um aberto por salão. Perder o id deixava a contagem viva e inalcançável — sem consultar,
 * sem aplicar, sem cancelar — e impedia abrir outra, para sempre.
 *
 * <p>Contagem de salão leva horas e se faz andando com o celular na mão. Supor que a aba sobrevive
 * a isso era supor errado.
 *
 * <p>Devolve {@link Optional} vazio, e não erro: não haver contagem aberta é o estado normal de
 * quase todo dia.
 */
public class ConsultarInventarioAbertoUseCase {

    private final InventariosJdbc inventarios;

    public ConsultarInventarioAbertoUseCase(InventariosJdbc inventarios) {
        this.inventarios = inventarios;
    }

    @PreAuthorize("hasAuthority('" + Permissao.ESTOQUE_LER + "')")
    @Transactional(readOnly = true)
    public Optional<InventarioDeEstoque> executar() {
        return inventarios.aberto();
    }
}
