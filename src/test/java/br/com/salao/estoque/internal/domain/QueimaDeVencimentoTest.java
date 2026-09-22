package br.com.salao.estoque.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.shared.dinheiro.Money;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RT-EST-012 — queimar o que vai vencer, sem perder dinheiro (RN-EST-037 a 039). */
class QueimaDeVencimentoTest {

    private static final BigDecimal TRINTA = new BigDecimal("30");

    private static MotorDePreco.Produto produto(String custo, String preco, Integer diasParaVencer) {
        return new MotorDePreco.Produto(UUID.randomUUID(), "Máscara 500g",
                Money.de(custo), preco == null ? null : Money.de(preco),
                new BigDecimal("10"), 5, diasParaVencer);
    }

    private static MotorDePreco.Sugestao avaliar(String custo, String preco, Integer dias) {
        return MotorDePreco.avaliar(produto(custo, preco, dias), TRINTA).orElseThrow();
    }

    @Test
    @DisplayName("na última semana a queima vai até o custo médio, e não abaixo")
    void ultima_semana_no_custo() {
        // Custo 100,00: na última semana o chão é o próprio custo → 109,90 na régua.
        var sugestao = avaliar("100.00", "139.90", 5);

        assertThat(sugestao.motivo()).isEqualTo(MotivoDaSugestao.QUEIMA_DE_VENCIMENTO);
        assertThat(sugestao.precoSugerido()).isEqualTo(Money.de("109.90"));
        assertThat(sugestao.racional()).contains("5 dias").contains("até o custo médio");
    }

    @Test
    @DisplayName("os quatro degraus: quanto mais perto de vencer, mais desconto")
    void quatro_degraus() {
        // Custo 100,00 e margem de 30%: 22,5% · 15% · 7,5% · 0%.
        assertThat(avaliar("100.00", "159.90", 25).precoSugerido()).isEqualTo(Money.de("129.90"));
        assertThat(avaliar("100.00", "159.90", 18).precoSugerido()).isEqualTo(Money.de("119.90"));
        assertThat(avaliar("100.00", "159.90", 10).precoSugerido()).isEqualTo(Money.de("109.90"));
        assertThat(avaliar("100.00", "159.90", 3).precoSugerido()).isEqualTo(Money.de("109.90"));
    }

    @Test
    @DisplayName("as bordas dos degraus: 30, 22, 21, 15, 14, 8, 7")
    void bordas_dos_degraus() {
        assertThat(MotorDePreco.fatorDaQueima(30)).isEqualByComparingTo("0.75");
        assertThat(MotorDePreco.fatorDaQueima(22)).isEqualByComparingTo("0.75");
        assertThat(MotorDePreco.fatorDaQueima(21)).isEqualByComparingTo("0.50");
        assertThat(MotorDePreco.fatorDaQueima(15)).isEqualByComparingTo("0.50");
        assertThat(MotorDePreco.fatorDaQueima(14)).isEqualByComparingTo("0.25");
        assertThat(MotorDePreco.fatorDaQueima(8)).isEqualByComparingTo("0.25");
        assertThat(MotorDePreco.fatorDaQueima(7)).isEqualByComparingTo("0");
        assertThat(MotorDePreco.fatorDaQueima(0)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a queima abre na janela de 30 dias, e não antes")
    void janela() {
        assertThat(avaliar("100.00", "159.90", 30).motivo())
                .isEqualTo(MotivoDaSugestao.QUEIMA_DE_VENCIMENTO);
        // A 31 dias não é queima: com margem sadia e preço na régua, nada a dizer.
        assertThat(MotorDePreco.avaliar(produto("100.00", "159.90", 31), TRINTA)).isEmpty();
    }

    @Test
    @DisplayName("lote vencido não entra na queima: vencido não se vende")
    void vencido_fica_de_fora() {
        // A consulta nem manda dias negativos; se mandasse, o motor não queimaria.
        assertThat(MotorDePreco.avaliar(produto("100.00", "159.90", -3), TRINTA)).isEmpty();
    }

    @Test
    @DisplayName("produto que vai vencer não sobe de preço, mesmo abaixo da margem mínima")
    void nunca_sobe() {
        // Preço de 38,00 sobre custo de 31,20 está abaixo dos 30% — sem a queima, a sugestão seria
        // SUBIR para 49,90. A 10 dias de vencer, a queima admite 7,5%: 33,54 → 39,90, que é mais que
        // 38,00. Nada a sugerir, em vez de cair no "suba o preço".
        assertThat(MotorDePreco.avaliar(produto("31.20", "38.00", 10), TRINTA)).isEmpty();
    }

    @Test
    @DisplayName("produto sem preço que vai vencer recebe o preço de queima")
    void sem_preco() {
        var sugestao = avaliar("31.20", null, 5);

        assertThat(sugestao.motivo()).isEqualTo(MotivoDaSugestao.QUEIMA_DE_VENCIMENTO);
        assertThat(sugestao.precoSugerido()).isEqualTo(Money.de("39.90"));
    }

    @Test
    @DisplayName("a queima vem antes de todos os outros motivos")
    void vem_primeiro() {
        // Encalhado há 300 dias E vencendo: a queima responde.
        var entrada = new MotorDePreco.Produto(UUID.randomUUID(), "p", Money.de("100.00"),
                Money.de("199.90"), new BigDecimal("10"), 300, 5);

        assertThat(MotorDePreco.avaliar(entrada, TRINTA).orElseThrow().motivo())
                .isEqualTo(MotivoDaSugestao.QUEIMA_DE_VENCIMENTO);
    }

    @Test
    @DisplayName("só a queima tem o chão no custo")
    void so_a_queima_desce_ao_custo() {
        for (MotivoDaSugestao motivo : MotivoDaSugestao.values()) {
            assertThat(motivo.chaoNoCusto()).as(motivo.name())
                    .isEqualTo(motivo == MotivoDaSugestao.QUEIMA_DE_VENCIMENTO);
        }
    }

    @Test
    @DisplayName("nenhuma queima fica abaixo do custo, em combinação nenhuma")
    void nunca_abaixo_do_custo() {
        for (int custo = 1; custo <= 400; custo += 3) {
            for (int preco = 0; preco <= 600; preco += 11) {
                for (int dias = 0; dias <= 30; dias++) {
                    for (String margem : new String[] {"0", "15", "30", "120"}) {
                        var entrada = new MotorDePreco.Produto(UUID.randomUUID(), "p",
                                Money.de(custo), preco == 0 ? null : Money.de(preco),
                                new BigDecimal("5"), 3, dias);
                        var sugestao = MotorDePreco.avaliar(entrada, new BigDecimal(margem));
                        if (sugestao.isEmpty()) {
                            continue;
                        }
                        assertThat(sugestao.get().motivo())
                                .isEqualTo(MotivoDaSugestao.QUEIMA_DE_VENCIMENTO);
                        assertThat(sugestao.get().precoSugerido())
                                .as("custo %d, preço %d, %d dias, margem %s", custo, preco, dias,
                                        margem)
                                .isGreaterThanOrEqualTo(Money.de(custo));
                        // E nunca sobe: a queima só baixa.
                        if (preco != 0) {
                            assertThat(sugestao.get().precoSugerido())
                                    .isLessThan(Money.de(preco));
                        }
                    }
                }
            }
        }
    }
}
