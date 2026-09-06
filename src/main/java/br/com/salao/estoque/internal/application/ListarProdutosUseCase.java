package br.com.salao.estoque.internal.application;

import br.com.salao.estoque.api.ProdutoResumo;
import br.com.salao.estoque.internal.infra.ProdutosJdbc;
import br.com.salao.iam.api.Permissao;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EST-001 — o catálogo de produtos.
 *
 * <p>Dois filtros porque são duas telas diferentes: a de cadastro mostra tudo, inclusive o que foi
 * desativado; a do balcão mostra o que dá para vender agora. Uma listagem só, com a decisão no
 * chamador, faria a tela de venda receber tinta de coloração.
 */
public class ListarProdutosUseCase {

    private final ProdutosJdbc produtos;

    public ListarProdutosUseCase(ProdutosJdbc produtos) {
        this.produtos = produtos;
    }

    @PreAuthorize("hasAuthority('" + Permissao.ESTOQUE_LER + "')")
    @Transactional(readOnly = true)
    public List<ProdutoResumo> executar(boolean somenteAtivos, boolean somenteVendaveis) {
        return produtos.listar(somenteAtivos, somenteVendaveis);
    }
}
