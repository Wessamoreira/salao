package br.com.salao.shared.dinheiro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    @DisplayName("normaliza a escala para que a igualdade não dependa de como o número foi escrito")
    void normaliza_escala() {
        assertThat(Money.de("10.00")).isEqualTo(Money.de("10.0000"));
        assertThat(Money.de("10").valor().scale()).isEqualTo(Money.ESCALA);
    }

    @Test
    @DisplayName("arredonda HALF_UP na quarta casa")
    void arredonda_half_up() {
        assertThat(Money.de("0.00005")).isEqualTo(Money.de("0.0001"));
        assertThat(Money.de("0.00004")).isEqualTo(Money.de("0.0000"));
    }

    @Test
    @DisplayName("rateio de desconto soma exatamente o valor original")
    void rateio_soma_exata() {
        // O caso que faz o fechamento do mês não bater (risco R-06): R$ 10,00 entre três itens
        // iguais dá 3,3333... e a soma ingênua devolve 9,9999.
        Money desconto = Money.de("10.00");
        List<Money> pesos = List.of(Money.de("100"), Money.de("100"), Money.de("100"));

        List<Money> partes = desconto.ratearProporcionalmente(pesos);

        assertThat(Money.somaDe(partes))
                .as("a última parte absorve a sobra de arredondamento")
                .isEqualTo(desconto);
        assertThat(partes).hasSize(3);
    }

    @Test
    @DisplayName("rateio respeita pesos diferentes")
    void rateio_proporcional() {
        Money desconto = Money.de("30.00");
        List<Money> pesos = List.of(Money.de("100"), Money.de("200"));

        List<Money> partes = desconto.ratearProporcionalmente(pesos);

        assertThat(partes.get(0)).isEqualTo(Money.de("10.00"));
        assertThat(partes.get(1)).isEqualTo(Money.de("20.00"));
        assertThat(Money.somaDe(partes)).isEqualTo(desconto);
    }

    @Test
    @DisplayName("rateio com peso total zero concentra na primeira parte em vez de perder o valor")
    void rateio_com_peso_zero() {
        Money valor = Money.de("5.00");

        List<Money> partes = valor.ratearProporcionalmente(List.of(Money.ZERO, Money.ZERO));

        assertThat(Money.somaDe(partes)).isEqualTo(valor);
        assertThat(partes.get(0)).isEqualTo(valor);
    }

    @Test
    void rateio_sem_pesos_e_erro() {
        assertThatThrownBy(() -> Money.de("1").ratearProporcionalmente(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("percentual em forma decimal: 0,4000 é 40%")
    void percentual() {
        assertThat(Money.de("250.00").percentual(new BigDecimal("0.4000")))
                .isEqualTo(Money.de("100.00"));
    }

    @Test
    void comparacoes() {
        assertThat(Money.de("10").maiorQue(Money.de("9.9999"))).isTrue();
        assertThat(Money.de("-1").ehNegativo()).isTrue();
        assertThat(Money.ZERO.ehZero()).isTrue();
        assertThat(Money.de("10").subtrair(Money.de("10")).ehZero()).isTrue();
    }
}
