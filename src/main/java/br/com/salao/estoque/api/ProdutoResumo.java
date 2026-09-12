package br.com.salao.estoque.api;

import br.com.salao.shared.dinheiro.Money;
import java.util.Optional;
import java.util.UUID;

/**
 * RT-EST-001 — o produto como as telas e os outros módulos o veem.
 *
 * @param precoVenda nulo é cadastro válido e venda inválida — o produto pode existir antes de
 *                   alguém decidir quanto cobrar, e quem recusa é a venda (RN-EST-002)
 */
public record ProdutoResumo(
        UUID id,
        String nome,
        String descricao,
        FinalidadeDoProduto finalidade,
        UnidadeDeMedida unidade,
        Money precoVenda,
        /** RT-EST-009 — exatamente o que o leitor emite, ou nulo. */
        String codigoBarras,
        boolean ativo,
        int versao) {

    /** Se dá para pôr numa comanda hoje: serve para venda, está ativo e tem preço. */
    public boolean vendavel() {
        return ativo && finalidade.podeSerVendido() && precoVenda != null;
    }

    public Optional<Money> preco() {
        return Optional.ofNullable(precoVenda);
    }
}
