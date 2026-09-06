package br.com.salao.estoque.internal.application;

import br.com.salao.estoque.api.LoteResumo;
import br.com.salao.estoque.internal.infra.LotesJdbc;
import br.com.salao.iam.api.Permissao;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EST-002 — os lotes de um produto, na ordem do FEFO.
 *
 * <p>Vence primeiro, sai primeiro. A ordem vem pronta do banco porque é a mesma que RT-EST-004 vai
 * consumir — deixar cada chamador reinventar o critério é como metade das implementações de FEFO
 * erra, e o erro só aparece quando alguma coisa vence no armário.
 */
public class ListarLotesUseCase {

    private final LotesJdbc lotes;

    public ListarLotesUseCase(LotesJdbc lotes) {
        this.lotes = lotes;
    }

    /**
     * @param somenteComSaldo a tela de estoque pede {@code true}; a de histórico, {@code false} —
     *                        lote zerado explica de onde saiu o que já foi vendido
     */
    @PreAuthorize("hasAuthority('" + Permissao.ESTOQUE_LER + "')")
    @Transactional(readOnly = true)
    public List<LoteResumo> executar(UUID produtoId, boolean somenteComSaldo) {
        return lotes.doProduto(produtoId, somenteComSaldo);
    }
}
