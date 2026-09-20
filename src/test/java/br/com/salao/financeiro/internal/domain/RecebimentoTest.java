package br.com.salao.financeiro.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.financeiro.api.FormaDePagamento;
import br.com.salao.shared.dinheiro.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RT-FIN-012 — quando cada parcela cai, e com quanto (RN-FIN-038). */
class RecebimentoTest {

    /** Sexta-feira. */
    private static final LocalDate VENDA = LocalDate.parse("2026-09-18");

    private static TaxaContratada taxa(FaixaDeTaxa faixa, String percentual, int dias) {
        return new TaxaContratada(faixa, new BigDecimal(percentual), dias);
    }

    @Test
    @DisplayName("débito em D+1: sexta vende, segunda cai — sábado não liquida")
    void debito() {
        var previsao = Recebimento.prever(Money.de("100.00"), 1, VENDA,
                taxa(FaixaDeTaxa.DEBITO, "1.99", 1));

        assertThat(previsao.primeiraParcela()).isEqualTo(LocalDate.parse("2026-09-21"));
        assertThat(previsao.taxaTotal().valor()).isEqualByComparingTo("1.99");
        assertThat(previsao.diasParaReceber()).isEqualTo(1);
    }

    @Test
    @DisplayName("crédito à vista em D+30, empurrado do domingo para a segunda")
    void credito_a_vista() {
        var previsao = Recebimento.prever(Money.de("200.00"), 1, VENDA,
                taxa(FaixaDeTaxa.CREDITO_A_VISTA, "3.49", 30));

        assertThat(previsao.primeiraParcela()).isEqualTo(LocalDate.parse("2026-10-19"));
        assertThat(previsao.taxaTotal().valor()).isEqualByComparingTo("6.98");
    }

    @Test
    @DisplayName("toda parcela conta do dia da venda: a segunda não herda o empurrão da primeira")
    void parcelas_contam_da_venda() {
        var cronograma = Recebimento.cronograma(Money.de("300.00"), Money.de("12.00"), 3, VENDA, 30);

        assertThat(cronograma).extracting(Recebimento.Parcela::quando).containsExactly(
                LocalDate.parse("2026-10-19"),  // 18/10 é domingo
                LocalDate.parse("2026-11-17"),  // terça: sem empurrão herdado
                LocalDate.parse("2026-12-17"));
    }

    @Test
    @DisplayName("a soma das parcelas é exatamente o valor da venda, e a última absorve a sobra")
    void rateio_fecha() {
        var cronograma = Recebimento.cronograma(Money.de("100.00"), Money.de("3.49"), 3, VENDA, 30);

        assertThat(Money.somaDe(cronograma.stream().map(Recebimento.Parcela::bruto).toList()))
                .isEqualTo(Money.de("100.00"));
        assertThat(Money.somaDe(cronograma.stream().map(Recebimento.Parcela::taxa).toList()))
                .isEqualTo(Money.de("3.49"));
        assertThat(Money.somaDe(cronograma.stream().map(Recebimento.Parcela::liquido).toList()))
                .isEqualTo(Money.de("96.51"));
    }

    @Test
    @DisplayName("o líquido de cada parcela é o bruto menos a taxa dela")
    void liquido_por_parcela() {
        var cronograma = Recebimento.cronograma(Money.de("200.00"), Money.de("8.00"), 2, VENDA, 30);

        assertThat(cronograma).allSatisfy(parcela ->
                assertThat(parcela.liquido()).isEqualTo(parcela.bruto().subtrair(parcela.taxa())));
        assertThat(cronograma.getFirst().liquido().valor()).isEqualByComparingTo("96.00");
    }

    @Test
    @DisplayName("zero parcela é tratada como uma: venda antiga não quebra a tela")
    void sem_parcela_informada() {
        assertThat(Recebimento.cronograma(Money.de("50.00"), Money.ZERO, 0, VENDA, 1)).hasSize(1);
    }

    // ------------------------------------------------------------------ a faixa

    @Test
    @DisplayName("a faixa sai da forma e do número de parcelas")
    void faixa() {
        assertThat(FaixaDeTaxa.de(FormaDePagamento.DEBITO, 1)).isEqualTo(FaixaDeTaxa.DEBITO);
        assertThat(FaixaDeTaxa.de(FormaDePagamento.CREDITO, 1))
                .isEqualTo(FaixaDeTaxa.CREDITO_A_VISTA);
        assertThat(FaixaDeTaxa.de(FormaDePagamento.CREDITO, 2)).isEqualTo(FaixaDeTaxa.CREDITO_2_6);
        assertThat(FaixaDeTaxa.de(FormaDePagamento.CREDITO, 6)).isEqualTo(FaixaDeTaxa.CREDITO_2_6);
        assertThat(FaixaDeTaxa.de(FormaDePagamento.CREDITO, 7)).isEqualTo(FaixaDeTaxa.CREDITO_7_12);
        assertThat(FaixaDeTaxa.de(FormaDePagamento.CREDITO, 12)).isEqualTo(FaixaDeTaxa.CREDITO_7_12);
        // Acima de 12 não recusa: a venda já aconteceu, e inventar erro aqui não desfaz nada.
        assertThat(FaixaDeTaxa.de(FormaDePagamento.CREDITO, 18)).isEqualTo(FaixaDeTaxa.CREDITO_7_12);
    }

    @Test
    @DisplayName("o que não passa por adquirente não tem faixa")
    void sem_faixa() {
        for (FormaDePagamento forma : FormaDePagamento.values()) {
            if (!forma.ehCartao()) {
                assertThat(FaixaDeTaxa.de(forma, 1)).as(forma.name()).isNull();
            }
        }
        assertThat(FaixaDeTaxa.de(null, 1)).isNull();
    }

    @Test
    @DisplayName("o percentual é percentual: 3,49% de R$ 100 é R$ 3,49")
    void percentual() {
        assertThat(taxa(FaixaDeTaxa.CREDITO_A_VISTA, "3.49", 30).sobre(Money.de("100.00")).valor())
                .isEqualByComparingTo("3.49");
        assertThat(taxa(FaixaDeTaxa.DEBITO, "0", 1).sobre(Money.de("100.00"))).isEqualTo(Money.ZERO);
    }
}
