package br.com.salao.financeiro.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RT-FIN-009 — o mesmo valor escrito de dois jeitos, e o jeito certo de errar. */
class FormatoDeNumeroTest {

    @Test
    @DisplayName("brasileiro: ponto de milhar, vírgula de centavo, com ou sem R$")
    void brasileiro() {
        assertThat(FormatoDeNumero.BRASILEIRO.ler("1.234,56")).isEqualByComparingTo("1234.56");
        assertThat(FormatoDeNumero.BRASILEIRO.ler("R$ 1.234,56")).isEqualByComparingTo("1234.56");
        assertThat(FormatoDeNumero.BRASILEIRO.ler("10")).isEqualByComparingTo("10");
        assertThat(FormatoDeNumero.BRASILEIRO.ler("(10,00)")).isEqualByComparingTo("-10");
    }

    @Test
    @DisplayName("internacional: o contrário")
    void internacional() {
        assertThat(FormatoDeNumero.INTERNACIONAL.ler("1,234.56")).isEqualByComparingTo("1234.56");
        assertThat(FormatoDeNumero.INTERNACIONAL.ler("10.5")).isEqualByComparingTo(new BigDecimal("10.5"));
    }

    @Test
    @DisplayName("vazio é nulo; texto que não é número é recusado com o próprio texto")
    void vazio_e_invalido() {
        assertThat(FormatoDeNumero.BRASILEIRO.ler("  ")).isNull();
        assertThatThrownBy(() -> FormatoDeNumero.BRASILEIRO.ler("dez reais"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dez reais");
    }

    @Test
    @DisplayName("formato trocado é recusado, e nunca vira outro valor — nos dois sentidos")
    void formato_trocado_recusa() {
        assertThatThrownBy(() -> FormatoDeNumero.INTERNACIONAL.ler("1.234,56"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FormatoDeNumero.BRASILEIRO.ler("10.50"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FormatoDeNumero.BRASILEIRO.ler("1,23456"))
                .as("mais casas do que a coluna guarda").isInstanceOf(IllegalArgumentException.class);
        assertThat(FormatoDeNumero.BRASILEIRO.ler("12.345.678,9")).isEqualByComparingTo("12345678.9");
    }

    @Test
    @DisplayName("a votação pelas amostras, e brasileiro na dúvida")
    void detectar() {
        assertThat(FormatoDeNumero.detectar(List.of("1.234,56", "10,00"))).isEqualTo(FormatoDeNumero.BRASILEIRO);
        assertThat(FormatoDeNumero.detectar(List.of("1,234.56", "10.00"))).isEqualTo(FormatoDeNumero.INTERNACIONAL);
        assertThat(FormatoDeNumero.detectar(List.of("1.234"))).isEqualTo(FormatoDeNumero.BRASILEIRO);
        assertThat(FormatoDeNumero.detectar(List.of("100", "250"))).isEqualTo(FormatoDeNumero.BRASILEIRO);
    }
}
