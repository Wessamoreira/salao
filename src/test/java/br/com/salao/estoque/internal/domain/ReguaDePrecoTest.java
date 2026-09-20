package br.com.salao.estoque.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.shared.dinheiro.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RT-EST-010 — a escada de 9,90 (RN-EST-032). */
class ReguaDePrecoTest {

    private static void arredonda(String de, String para) {
        assertThat(ReguaDePreco.arredondar(Money.de(de))).as(de).isEqualTo(Money.de(para));
    }

    @Test
    @DisplayName("sobe até o próximo degrau da escada")
    void sobe() {
        arredonda("47.83", "49.90");
        arredonda("7.20", "9.90");
        arredonda("112.40", "119.90");
        arredonda("10.00", "19.90");
        arredonda("0.01", "9.90");
    }

    @Test
    @DisplayName("quem já está na régua fica onde está")
    void ja_na_regua() {
        for (String preco : new String[] {"9.90", "19.90", "29.90", "49.90", "119.90"}) {
            arredonda(preco, preco);
            assertThat(ReguaDePreco.estaNaRegua(Money.de(preco))).as(preco).isTrue();
        }
    }

    @Test
    @DisplayName("preço quebrado não está na régua")
    void fora_da_regua() {
        for (String preco : new String[] {"38.00", "47.83", "10.00", "19.91", "19.89"}) {
            assertThat(ReguaDePreco.estaNaRegua(Money.de(preco))).as(preco).isFalse();
        }
    }

    @Test
    @DisplayName("arredondar nunca baixa o preço — é o que não deixa a régua furar a margem")
    void nunca_baixa() {
        for (int centavos = 1; centavos <= 30000; centavos += 7) {
            Money valor = new Money(new java.math.BigDecimal(centavos).movePointLeft(2));
            assertThat(ReguaDePreco.arredondar(valor)).as(valor.toString())
                    .isGreaterThanOrEqualTo(valor);
        }
    }

    @Test
    @DisplayName("zero vai para o primeiro degrau, e não para zero")
    void zero() {
        arredonda("0.00", "9.90");
    }
}
