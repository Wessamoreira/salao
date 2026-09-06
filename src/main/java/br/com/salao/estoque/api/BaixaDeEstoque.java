package br.com.salao.estoque.api;

import br.com.salao.shared.dinheiro.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * RT-EST-004 — o que saiu, de onde, e por quanto.
 *
 * <p>Uma baixa quase nunca é de um lote só. Vender três unidades quando o lote mais velho tem duas
 * tira duas de lá e uma do seguinte — e os dois lotes custaram diferente.
 *
 * @param custoMedioUnitario a média ponderada <strong>desta retirada</strong>, e não o custo médio
 *                           do produto (RT-EST-003). São números diferentes de propósito: este é o
 *                           que a venda efetivamente consumiu, e é ele que vira
 *                           {@code item_comanda.custo_medio_unitario}
 * @param parcelas           de qual lote saiu cada pedaço. É o que responde "de que caixa veio o
 *                           produto que a cliente levou" num recall
 */
public record BaixaDeEstoque(
        UUID produtoId,
        BigDecimal quantidade,
        Money custoMedioUnitario,
        Money custoTotal,
        List<ParcelaDaBaixa> parcelas) {

    /** Um pedaço da baixa, saído de um lote. */
    public record ParcelaDaBaixa(UUID loteId, BigDecimal quantidade, Money custoUnitario) {
    }
}
