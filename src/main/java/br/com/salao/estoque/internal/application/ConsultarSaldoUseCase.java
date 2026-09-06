package br.com.salao.estoque.internal.application;

import br.com.salao.estoque.api.SaldoDoProduto;
import br.com.salao.estoque.internal.infra.LotesJdbc;
import br.com.salao.iam.api.Permissao;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EST-003 — quanto há no armário, e quanto isso vale.
 *
 * <p>Nada aqui é lido de coluna: saldo, custo médio e valor são calculados dos movimentos a cada
 * consulta. É a mesma disciplina de RN-EST-006, um nível acima do lote.
 *
 * <p>O custo médio é a <strong>ponderação dos lotes que ainda têm unidades</strong>. É ele que
 * transforma "vendi por 45" em "ganhei 18" — e é a única resposta possível quando a prateleira tem
 * unidades que custaram vinte e unidades que custaram vinte e cinco.
 */
public class ConsultarSaldoUseCase {

    private final LotesJdbc lotes;

    public ConsultarSaldoUseCase(LotesJdbc lotes) {
        this.lotes = lotes;
    }

    @PreAuthorize("hasAuthority('" + Permissao.ESTOQUE_LER + "')")
    @Transactional(readOnly = true)
    public Optional<SaldoDoProduto> doProduto(UUID produtoId) {
        return lotes.saldoDoProduto(produtoId);
    }

    /**
     * @param somenteComEstoque a tela de reposição pede {@code false} — o que interessa lá é
     *                          justamente o que está zerado
     */
    @PreAuthorize("hasAuthority('" + Permissao.ESTOQUE_LER + "')")
    @Transactional(readOnly = true)
    public List<SaldoDoProduto> deTodos(boolean somenteComEstoque) {
        return lotes.saldos(somenteComEstoque);
    }
}
