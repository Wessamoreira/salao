package br.com.salao.atendimento.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.atendimento.api.ComprovanteDeAtendimento;
import br.com.salao.atendimento.api.ComprovanteDeAtendimento.LinhaDoComprovante;
import br.com.salao.atendimento.api.ComprovanteDeAtendimento.PagamentoDoComprovante;
import br.com.salao.shared.dinheiro.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RT-FIN-008 — o comprovante em 48 colunas. */
class TextoDoComprovanteTest {

    /** 23h50 de 12/09 em São Paulo é 02h50 de 13/09 em UTC: o fuso decide a data impressa. */
    private static final Instant FECHADA = Instant.parse("2026-09-13T02:50:00Z");

    private ComprovanteDeAtendimento comprovante(List<LinhaDoComprovante> itens,
                                                 List<PagamentoDoComprovante> pagamentos,
                                                 String desconto) {
        return new ComprovanteDeAtendimento("Studio Bertoldo", 128, FECHADA,
                "America/Sao_Paulo", "Maria Silva", itens,
                Money.de("215"), Money.de(desconto), Money.de("193.50"), pagamentos);
    }

    private LinhaDoComprovante coloracao() {
        return new LinhaDoComprovante("Coloração", BigDecimal.ONE, Money.de("170"),
                Money.de("17"), Money.de("153"));
    }

    @Test
    @DisplayName("nenhuma linha passa de 48 colunas")
    void cabe_na_bobina() {
        // É o invariante que faz o papel sair legível: a térmica não quebra linha, ela CORTA.
        // Uma linha de 50 colunas perde os dois últimos caracteres — que é justamente onde o
        // valor está.
        String texto = TextoDoComprovante.render(comprovante(
                List.of(coloracao(),
                        new LinhaDoComprovante(
                                "Escova modeladora com finalização e tratamento de brilho",
                                BigDecimal.ONE, Money.de("45"), Money.ZERO, Money.de("45"))),
                List.of(new PagamentoDoComprovante("Crédito", 2, Money.de("193.50"))),
                "21.50"));

        assertThat(texto.lines())
                .allSatisfy(linha -> assertThat(linha.length())
                        .as("linha \"%s\"", linha)
                        .isLessThanOrEqualTo(TextoDoComprovante.COLUNAS));
    }

    @Test
    @DisplayName("a coluna de dinheiro fecha na borda direita")
    void dinheiro_alinhado_a_direita() {
        // Em largura fixa, é o alinhamento que transforma uma lista de valores em coluna somável.
        String texto = TextoDoComprovante.render(comprovante(List.of(coloracao()),
                List.of(new PagamentoDoComprovante("Pix", 1, Money.de("193.50"))), "21.50"));

        // TODA linha que carrega valor, e não só as de total. A versão anterior deste teste
        // olhava apenas "TOTAL" e "Bruto" — e por isso não viu que os itens fechavam na coluna
        // 46 enquanto os totais fechavam na 48: o recibo tinha DUAS bordas direitas.
        assertThat(texto.lines().filter(l -> l.matches(".*\\d,\\d\\d$")))
                .isNotEmpty()
                .allSatisfy(l -> assertThat(l.length())
                        .as("linha de valor \"%s\"", l)
                        .isEqualTo(TextoDoComprovante.COLUNAS));
    }

    @Test
    @DisplayName("sempre avisa que não é documento fiscal")
    void avisa_que_nao_e_fiscal() {
        // R-UX-16. A frase mora no renderizador, e não em quem chama, para não sumir num dos
        // caminhos de saída — um recibo que não avisa o que não é acaba usado como nota.
        String texto = TextoDoComprovante.render(
                comprovante(List.of(coloracao()), List.of(), "0"));

        assertThat(texto).contains("NÃO É DOCUMENTO FISCAL");
    }

    @Test
    @DisplayName("a data é a do salão, não a do servidor")
    void data_no_fuso_do_salao() {
        // 02h50 UTC de 13/09 é 23h50 de 12/09 em São Paulo. Uma cliente atendida quase à
        // meia-noite não quer um recibo de amanhã.
        String texto = TextoDoComprovante.render(
                comprovante(List.of(coloracao()), List.of(), "0"));

        assertThat(texto).contains("12/09/2026 às 23:50").doesNotContain("13/09");
    }

    @Test
    @DisplayName("o detalhe do item é recuado; o rótulo de total não")
    void hierarquia_pelo_recuo() {
        // Em largura fixa não há negrito nem tamanho: o recuo é a única hierarquia disponível.
        // Sem ele, "1 x 170,00" e "Bruto" começam na mesma coluna e o recibo vira uma lista
        // plana de números.
        String texto = TextoDoComprovante.render(comprovante(List.of(coloracao()),
                List.of(new PagamentoDoComprovante("Pix", 1, Money.de("193.50"))), "21.50"));

        assertThat(texto.lines().filter(l -> l.contains(" x ")))
                .singleElement()
                .satisfies(l -> assertThat(l).startsWith("  "));
        assertThat(texto.lines().filter(l -> l.contains("desconto")))
                .allSatisfy(l -> assertThat(l).startsWith("  "));
        assertThat(texto.lines().filter(l -> l.startsWith("TOTAL")))
                .as("o total não é detalhe de ninguém").isNotEmpty();
    }

    @Test
    @DisplayName("desconto zero não vira linha")
    void desconto_zero_nao_imprime() {
        // Bobina é papel: uma linha de "desconto 0,00" em cada item é meio recibo a mais.
        String texto = TextoDoComprovante.render(comprovante(
                List.of(new LinhaDoComprovante("Corte", BigDecimal.ONE, Money.de("80"),
                        Money.ZERO, Money.de("80"))),
                List.of(), "0"));

        assertThat(texto).doesNotContain("desconto").doesNotContain("Desconto");
    }

    @Test
    @DisplayName("nome comprido é cortado sem reticências")
    void corte_seco() {
        String longo = "Escova modeladora com finalização e tratamento de brilho profundo";
        String texto = TextoDoComprovante.render(comprovante(
                List.of(new LinhaDoComprovante(longo, BigDecimal.ONE, Money.de("45"),
                        Money.ZERO, Money.de("45"))),
                List.of(), "0"));

        assertThat(texto).doesNotContain("…").doesNotContain("...");
        assertThat(texto).contains(longo.substring(0, TextoDoComprovante.COLUNAS));
    }

    @Test
    @DisplayName("quantidade inteira sai sem casas decimais")
    void quantidade_inteira() {
        String texto = TextoDoComprovante.render(comprovante(
                List.of(new LinhaDoComprovante("Corte", new BigDecimal("1.000"),
                        Money.de("80"), Money.ZERO, Money.de("80"))),
                List.of(), "0"));

        assertThat(texto).contains("1 x").doesNotContain("1,000 x");
    }

    @Test
    @DisplayName("parcela só aparece quando é mais de uma")
    void parcela_unica_nao_aparece() {
        String texto = TextoDoComprovante.render(comprovante(List.of(coloracao()),
                List.of(new PagamentoDoComprovante("Pix", 1, Money.de("100")),
                        new PagamentoDoComprovante("Crédito", 3, Money.de("93.50"))), "21.50"));

        assertThat(texto).contains("Crédito 3x").doesNotContain("Pix 1x");
    }
}
