package br.com.salao.estoque.api;

import br.com.salao.shared.dinheiro.Money;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * RT-EST-003 — quanto há e quanto vale.
 *
 * <p>Tudo aqui é <strong>projeção</strong>: nenhuma destas três coisas é coluna que alguém
 * atualiza. Saldo é a soma dos movimentos; custo médio é a média ponderada dos lotes que ainda têm
 * unidades; valor é o produto dos dois.
 *
 * @param custoMedio <strong>nulo</strong> quando não há nada em estoque — e nulo não é zero: zero
 *                   significaria "tenho unidades que não custaram nada", que é o caso da amostra
 *                   do fornecedor. Sem estoque não há média nenhuma a tirar
 * @param valorEmEstoque quanto o salão tem parado na prateleira. É a resposta do inventário
 *                       contábil, e zero quando não há nada
 */
public record SaldoDoProduto(
        UUID produtoId,
        String nome,
        UnidadeDeMedida unidade,
        BigDecimal quantidade,
        Money custoMedio,
        Money valorEmEstoque) {

    public boolean semEstoque() {
        return quantidade.signum() <= 0;
    }
}
