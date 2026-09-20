package br.com.salao.estoque.internal.domain;

import br.com.salao.shared.dinheiro.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * RT-EST-010 — a escada de 9,90 (RN-EST-032).
 *
 * <p>Todo preço sugerido termina em 9,90: 9,90 · 19,90 · 29,90 · 39,90… Decisão do dono.
 *
 * <h2>Sempre para cima</h2>
 *
 * <p>E é isso que faz a régua nunca brigar com o chão de margem: arredondar para baixo poderia levar o
 * preço para debaixo do custo mínimo, e aí a régua estaria desfazendo a única garantia da rotina.
 *
 * <p>O efeito conhecido, e aceito: em produto barato a margem sugerida sobe bem acima da mínima —
 * R$ 5,20 calculado vira R$ 9,90. A tela mostra a margem resultante para a decisão ser tomada com o
 * número à vista.
 */
public final class ReguaDePreco {

    /** O degrau: de dez em dez reais, um centavo antes da dezena. */
    private static final BigDecimal DEGRAU = new BigDecimal("10");
    private static final BigDecimal FOLGA = new BigDecimal("0.10");

    private ReguaDePreco() {
    }

    /** O menor preço da régua que não é menor que {@code valor}. */
    public static Money arredondar(Money valor) {
        BigDecimal degraus = valor.valor().add(FOLGA)
                .divide(DEGRAU, 0, RoundingMode.CEILING)
                .max(BigDecimal.ONE);
        return new Money(degraus.multiply(DEGRAU).subtract(FOLGA));
    }

    /** Se o preço já está na régua — o que dispensa sugerir arredondá-lo. */
    public static boolean estaNaRegua(Money valor) {
        return arredondar(valor).equals(valor);
    }
}
