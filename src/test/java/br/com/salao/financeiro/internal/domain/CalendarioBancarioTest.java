package br.com.salao.financeiro.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RT-FIN-012 — em que dias o banco liquida (RN-FIN-038). */
class CalendarioBancarioTest {

    private static LocalDate dia(String texto) {
        return LocalDate.parse(texto);
    }

    @Test
    @DisplayName("a Páscoa sai pelo algoritmo, e não de uma tabela que envelhece")
    void pascoa() {
        assertThat(CalendarioBancario.pascoa(2024)).isEqualTo(dia("2024-03-31"));
        assertThat(CalendarioBancario.pascoa(2025)).isEqualTo(dia("2025-04-20"));
        assertThat(CalendarioBancario.pascoa(2026)).isEqualTo(dia("2026-04-05"));
        assertThat(CalendarioBancario.pascoa(2027)).isEqualTo(dia("2027-03-28"));
    }

    @Test
    @DisplayName("os feriados que andam com a Páscoa andam junto")
    void feriados_moveis() {
        assertThat(CalendarioBancario.feriados(2026)).contains(
                dia("2026-02-16"), dia("2026-02-17"),  // carnaval
                dia("2026-04-03"),                      // sexta-feira santa
                dia("2026-06-04"));                     // corpus christi
    }

    @Test
    @DisplayName("as datas fixas, inclusive 20/11 e os dois sem liquidação de dezembro")
    void feriados_fixos() {
        assertThat(CalendarioBancario.feriados(2026)).contains(
                dia("2026-01-01"), dia("2026-04-21"), dia("2026-05-01"), dia("2026-09-07"),
                dia("2026-10-12"), dia("2026-11-02"), dia("2026-11-15"), dia("2026-11-20"),
                dia("2026-12-24"), dia("2026-12-25"), dia("2026-12-31"));
    }

    @Test
    @DisplayName("dia útil fica onde está")
    void dia_util_nao_anda() {
        assertThat(CalendarioBancario.proximoDiaUtil(dia("2026-09-18")))
                .isEqualTo(dia("2026-09-18"));
    }

    @Test
    @DisplayName("fim de semana cai para a segunda")
    void fim_de_semana() {
        assertThat(CalendarioBancario.proximoDiaUtil(dia("2026-10-18")))  // domingo
                .isEqualTo(dia("2026-10-19"));
        assertThat(CalendarioBancario.proximoDiaUtil(dia("2026-09-19")))  // sábado
                .isEqualTo(dia("2026-09-21"));
    }

    @Test
    @DisplayName("Natal na sexta empurra para a segunda seguinte")
    void natal() {
        assertThat(CalendarioBancario.proximoDiaUtil(dia("2026-12-24")))
                .isEqualTo(dia("2026-12-28"));
        assertThat(CalendarioBancario.proximoDiaUtil(dia("2026-12-25")))
                .isEqualTo(dia("2026-12-28"));
    }

    @Test
    @DisplayName("31/12 atravessa o ano: o 1º de janeiro também não liquida")
    void virada_do_ano() {
        assertThat(CalendarioBancario.proximoDiaUtil(dia("2026-12-31")))
                .isEqualTo(dia("2027-01-04"));
    }

    @Test
    @DisplayName("o salão abre no feriado; o banco não")
    void feriado_no_meio_da_semana() {
        // 7 de setembro de 2026 é uma segunda-feira: dia cheio de salão, e sem liquidação.
        assertThat(CalendarioBancario.ehDiaUtil(dia("2026-09-07"))).isFalse();
        assertThat(CalendarioBancario.proximoDiaUtil(dia("2026-09-07")))
                .isEqualTo(dia("2026-09-08"));
    }
}
