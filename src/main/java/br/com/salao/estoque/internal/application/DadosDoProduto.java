package br.com.salao.estoque.internal.application;

import br.com.salao.estoque.api.FinalidadeDoProduto;
import br.com.salao.estoque.api.UnidadeDeMedida;
import br.com.salao.shared.dinheiro.Money;

/**
 * RT-EST-001 — o que se informa ao cadastrar um produto.
 *
 * @param precoVenda opcional, e só para {@code VENDA} ou {@code AMBOS}. Nulo é cadastro válido: o
 *                   produto pode existir antes de alguém decidir quanto cobrar
 */
public record DadosDoProduto(
        String nome,
        String descricao,
        FinalidadeDoProduto finalidade,
        UnidadeDeMedida unidade,
        Money precoVenda) {
}
