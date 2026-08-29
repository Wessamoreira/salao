package br.com.salao.shared.dinheiro;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * RT-INF-004 / ADR-0009 — dinheiro.
 *
 * <p>Escala canônica 4 e {@link RoundingMode#HALF_UP} explícito, espelhando {@code numeric(19,4)}
 * no banco. A escala é normalizada no construtor: sem isso, {@code BigDecimal.equals} trata
 * {@code 10.00} e {@code 10.0000} como diferentes, e a igualdade de valores monetários passaria a
 * depender de como o número foi escrito.
 *
 * <p>{@code double} não aparece em lugar nenhum desta classe, e
 * {@code ArquiteturaTest.dinheiro_nunca_e_double_ou_float} garante que não apareça no resto.
 */
public record Money(BigDecimal valor) implements Comparable<Money> {

    public static final int ESCALA = 4;
    public static final RoundingMode ARREDONDAMENTO = RoundingMode.HALF_UP;
    public static final Money ZERO = new Money(BigDecimal.ZERO);

    public Money {
        Objects.requireNonNull(valor, "valor não pode ser nulo");
        valor = valor.setScale(ESCALA, ARREDONDAMENTO);
    }

    public static Money de(String valor) {
        return new Money(new BigDecimal(valor));
    }

    public static Money de(long valor) {
        return new Money(BigDecimal.valueOf(valor));
    }

    public Money somar(Money outro) {
        return new Money(valor.add(outro.valor));
    }

    public Money subtrair(Money outro) {
        return new Money(valor.subtract(outro.valor));
    }

    public Money multiplicar(BigDecimal fator) {
        return new Money(valor.multiply(fator));
    }

    /** Percentual em forma decimal: {@code 0.4000} significa 40%. */
    public Money percentual(BigDecimal percentualDecimal) {
        return multiplicar(percentualDecimal);
    }

    public Money negado() {
        return new Money(valor.negate());
    }

    public boolean ehZero() {
        return valor.signum() == 0;
    }

    public boolean ehNegativo() {
        return valor.signum() < 0;
    }

    public boolean maiorQue(Money outro) {
        return compareTo(outro) > 0;
    }

    public boolean menorQue(Money outro) {
        return compareTo(outro) < 0;
    }

    @Override
    public int compareTo(Money outro) {
        return valor.compareTo(outro.valor);
    }

    public static Money somaDe(List<Money> parcelas) {
        return parcelas.stream().reduce(ZERO, Money::somar);
    }

    /**
     * Rateia este valor entre {@code pesos}, proporcionalmente, de modo que a soma das partes seja
     * <strong>exatamente</strong> igual a este valor.
     *
     * <p>A última parte absorve a diferença de arredondamento. Sem essa regra, um desconto de
     * R$ 10,00 dividido entre três itens vira R$ 9,9999 ou R$ 10,0001 — e o fechamento do mês não
     * bate com a conta que o dono fez à mão, que é justamente o momento em que ele decide se o
     * sistema serve (risco R-06).
     *
     * <p>Peso total zero devolve tudo na primeira parte: não há proporção a aplicar, e perder o
     * valor em silêncio seria pior que concentrá-lo.
     */
    public List<Money> ratearProporcionalmente(List<Money> pesos) {
        if (pesos.isEmpty()) {
            throw new IllegalArgumentException("é preciso ao menos um peso para ratear");
        }
        Money total = somaDe(pesos);
        List<Money> partes = new ArrayList<>(pesos.size());

        if (total.ehZero()) {
            partes.add(this);
            for (int i = 1; i < pesos.size(); i++) {
                partes.add(ZERO);
            }
            return List.copyOf(partes);
        }

        Money acumulado = ZERO;
        for (int i = 0; i < pesos.size() - 1; i++) {
            BigDecimal proporcao = pesos.get(i).valor()
                    .divide(total.valor(), ESCALA + 6, ARREDONDAMENTO);
            Money parte = multiplicar(proporcao);
            partes.add(parte);
            acumulado = acumulado.somar(parte);
        }
        partes.add(subtrair(acumulado));

        return List.copyOf(partes);
    }

    @Override
    public String toString() {
        return valor.toPlainString();
    }
}
