package br.com.salao.estoque.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.shared.dinheiro.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RT-EST-010 — a conta que sugere o preço (RN-EST-030 a 032). */
class MotorDePrecoTest {

    private static final BigDecimal TRINTA = new BigDecimal("30");

    private static MotorDePreco.Produto produto(String custo, String preco, String saldo,
                                                Integer diasSemVenda) {
        return new MotorDePreco.Produto(UUID.randomUUID(), "Máscara 500g",
                custo == null ? null : Money.de(custo), preco == null ? null : Money.de(preco),
                new BigDecimal(saldo), diasSemVenda);
    }

    @Test
    @DisplayName("vende perto do custo: sugere subir até o chão, na régua")
    void margem_abaixo_do_minimo() {
        var sugestao = MotorDePreco.avaliar(produto("31.20", "38.00", "10", 5), TRINTA).orElseThrow();

        assertThat(sugestao.motivo()).isEqualTo(MotivoDaSugestao.MARGEM_ABAIXO_DO_MINIMO);
        // 31,20 × 1,30 = 40,56 → 49,90 na régua.
        assertThat(sugestao.precoSugerido()).isEqualTo(Money.de("49.90"));
        assertThat(sugestao.margemAtual()).isEqualByComparingTo("21.7949");
        assertThat(sugestao.racional()).contains("R$ 31,20").contains("21,8%").contains("30,0%");
    }

    @Test
    @DisplayName("produto sem preço de venda é o caso mais gritante de margem abaixo do mínimo")
    void sem_preco() {
        var sugestao = MotorDePreco.avaliar(produto("20.00", null, "4", 3), TRINTA).orElseThrow();

        assertThat(sugestao.motivo()).isEqualTo(MotivoDaSugestao.MARGEM_ABAIXO_DO_MINIMO);
        assertThat(sugestao.precoAtual()).isNull();
        assertThat(sugestao.margemAtual()).isNull();
        assertThat(sugestao.precoSugerido()).isEqualTo(Money.de("29.90"));
        assertThat(sugestao.racional()).contains("Sem preço de venda");
    }

    @Test
    @DisplayName("sem custo médio não há sugestão: chão sobre custo desconhecido é chão inventado")
    void sem_custo_medio() {
        assertThat(MotorDePreco.avaliar(produto(null, "38.00", "0", 200), TRINTA)).isEmpty();
        assertThat(MotorDePreco.avaliar(produto("0.00", "38.00", "0", 200), TRINTA)).isEmpty();
    }

    @Test
    @DisplayName("parado há 60 dias com estoque: sugere baixar até o chão")
    void encalhado() {
        // 10,00 de custo, chão 13,00 → 19,90. Preço de 49,90 está bem acima.
        var sugestao = MotorDePreco.avaliar(produto("10.00", "49.90", "8", 60), TRINTA)
                .orElseThrow();

        assertThat(sugestao.motivo()).isEqualTo(MotivoDaSugestao.ENCALHADO);
        assertThat(sugestao.precoSugerido()).isEqualTo(Money.de("19.90"));
        assertThat(sugestao.racional()).contains("60 dias sem vender").contains("8 em estoque");
    }

    @Test
    @DisplayName("encalhado só desce: no chão, não há promoção a fazer sem furar a margem")
    void encalhado_ja_no_chao() {
        // Custo 10,00, preço 19,90 — que É o chão na régua. Margem 99%, acima do dobro (60%),
        // mas baixar até o teto também não desce: 10 × 1,60 = 16,00 → 19,90 na régua.
        assertThat(MotorDePreco.avaliar(produto("10.00", "19.90", "8", 120), TRINTA)).isEmpty();
    }

    @Test
    @DisplayName("saldo baixo não encalha: não se faz promoção do último frasco")
    void encalhado_precisa_de_saldo() {
        assertThat(MotorDePreco.avaliar(produto("10.00", "49.90", "2", 200), TRINTA))
                .get().extracting(MotorDePreco.Sugestao::motivo)
                .isNotEqualTo(MotivoDaSugestao.ENCALHADO);
    }

    @Test
    @DisplayName("produto que nunca se moveu não encalha — não há desde quando contar")
    void sem_historico_nao_encalha() {
        assertThat(MotorDePreco.avaliar(produto("10.00", "19.90", "8", null), TRINTA)).isEmpty();
    }

    @Test
    @DisplayName("margem acima do dobro da mínima: sugere baixar até o dobro")
    void margem_alta() {
        // Custo 40,00: chão 52,00 → 59,90; teto (60%) 64,00 → 69,90. Preço 119,90 dá 200%.
        var sugestao = MotorDePreco.avaliar(produto("40.00", "119.90", "2", 3), TRINTA)
                .orElseThrow();

        assertThat(sugestao.motivo()).isEqualTo(MotivoDaSugestao.MARGEM_ALTA);
        assertThat(sugestao.precoSugerido()).isEqualTo(Money.de("69.90"));
        assertThat(sugestao.racional()).contains("acima do dobro da mínima");
    }

    @Test
    @DisplayName("margem sadia e preço fora da régua: só arredonda")
    void preco_quebrado() {
        // Custo 30,00: chão 39,00 → 39,90; teto 48,00 → 49,90. Preço 45,00 dá 50% — entre os dois.
        var sugestao = MotorDePreco.avaliar(produto("30.00", "45.00", "5", 3), TRINTA)
                .orElseThrow();

        assertThat(sugestao.motivo()).isEqualTo(MotivoDaSugestao.PRECO_QUEBRADO);
        assertThat(sugestao.precoSugerido()).isEqualTo(Money.de("49.90"));
        assertThat(sugestao.racional()).contains("fora da régua");
    }

    @Test
    @DisplayName("margem sadia e preço na régua: nada a dizer")
    void nada_a_sugerir() {
        assertThat(MotorDePreco.avaliar(produto("30.00", "49.90", "5", 3), TRINTA)).isEmpty();
    }

    @Test
    @DisplayName("um motivo por produto, e é o primeiro da ordem")
    void um_motivo_so() {
        // Encalhado E com margem abaixo do mínimo: vence o mínimo, que é o que dói.
        var sugestao = MotorDePreco.avaliar(produto("31.20", "38.00", "10", 300), TRINTA)
                .orElseThrow();

        assertThat(sugestao.motivo()).isEqualTo(MotivoDaSugestao.MARGEM_ABAIXO_DO_MINIMO);
    }

    @Test
    @DisplayName("o chão vale para toda combinação: nenhuma sugestão fica abaixo dele")
    void nunca_abaixo_do_chao() {
        for (int custo = 1; custo <= 400; custo += 3) {
            for (int preco = 0; preco <= 600; preco += 7) {
                for (int dias : new int[] {0, 59, 60, 400}) {
                    for (int saldo : new int[] {0, 2, 3, 50}) {
                        for (String margem : new String[] {"0", "15", "30", "120"}) {
                            var entrada = new MotorDePreco.Produto(UUID.randomUUID(), "p",
                                    Money.de(custo), preco == 0 ? null : Money.de(preco),
                                    new BigDecimal(saldo), dias);
                            BigDecimal minima = new BigDecimal(margem);
                            var sugestao = MotorDePreco.avaliar(entrada, minima);
                            if (sugestao.isEmpty()) {
                                continue;
                            }
                            Money chao = Money.de(custo).multiplicar(BigDecimal.ONE.add(
                                    minima.divide(new BigDecimal("100"), 10,
                                            RoundingMode.HALF_UP)));
                            assertThat(sugestao.get().precoSugerido())
                                    .as("custo %d, preço %d, margem %s", custo, preco, margem)
                                    .isGreaterThanOrEqualTo(chao);
                            assertThat(ReguaDePreco.estaNaRegua(sugestao.get().precoSugerido()))
                                    .as("na régua").isTrue();
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("margem mínima zero ainda tem chão: o custo")
    void margem_zero() {
        var sugestao = MotorDePreco.avaliar(produto("10.00", "5.00", "5", 3), BigDecimal.ZERO)
                .orElseThrow();

        assertThat(sugestao.motivo()).isEqualTo(MotivoDaSugestao.MARGEM_ABAIXO_DO_MINIMO);
        assertThat(sugestao.precoSugerido()).isEqualTo(Money.de("19.90"));
        assertThat(sugestao.precoSugerido()).isGreaterThanOrEqualTo(Money.de("10.00"));
    }
}
