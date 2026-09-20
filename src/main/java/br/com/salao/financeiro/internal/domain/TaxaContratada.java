package br.com.salao.financeiro.internal.domain;

import br.com.salao.shared.dinheiro.Money;
import java.math.BigDecimal;

/**
 * RT-FIN-012 — o que o salão negociou para uma faixa.
 *
 * @param percentual        em forma percentual: {@code 3.4900} é 3,49%
 * @param diasParaReceber   até a <strong>primeira</strong> parcela; as seguintes vêm de 30 em 30
 */
public record TaxaContratada(FaixaDeTaxa faixa, BigDecimal percentual, int diasParaReceber) {

    private static final BigDecimal CEM = new BigDecimal("100");

    /** Quanto a adquirente retém deste valor. */
    public Money sobre(Money valor) {
        return valor.percentual(percentual.divide(CEM, Money.ESCALA + 6, Money.ARREDONDAMENTO));
    }
}
