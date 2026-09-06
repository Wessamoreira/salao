package br.com.salao.estoque.internal.application;

import br.com.salao.shared.dinheiro.Money;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * RT-EST-002 — o que se informa ao receber uma caixa.
 *
 * @param codigo      o código impresso na embalagem, se houver. É por ele que um recall encontra o
 *                    que precisa sair de circulação
 * @param validade    nula quando o produto não tem validade impressa
 * @param recebidoEm  nula vira hoje. Existe porque a nota costuma ser lançada dias depois
 */
public record DadosDaEntrada(
        BigDecimal quantidade,
        Money custoUnitario,
        String codigo,
        LocalDate validade,
        LocalDate recebidoEm) {
}
