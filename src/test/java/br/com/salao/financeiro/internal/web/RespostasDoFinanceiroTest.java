package br.com.salao.financeiro.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.financeiro.api.ExtratoDoProfissional;
import br.com.salao.financeiro.api.FechamentoDoProfissional;
import br.com.salao.financeiro.api.FormaDoVale;
import br.com.salao.financeiro.api.PreviaDoFechamento;
import br.com.salao.financeiro.api.TipoDeLancamento;
import br.com.salao.financeiro.api.Vale;
import br.com.salao.shared.dinheiro.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RT-FIN-013 — o que a tela do financeiro recebe.
 *
 * <p>Duas coisas que teste de caso de uso não pega: {@code Money} serializa como
 * {@code {"valor": …}}, e método derivado de record não vira campo no JSON. Sem os DTOs, a tela
 * receberia dinheiro em duas formas e teria de refazer contas que já têm dono — e a segunda
 * cópia de uma conta é a que ninguém corrige.
 */
class RespostasDoFinanceiroTest {

    private static final UUID ANA = UUID.randomUUID();

    @Test
    @DisplayName("extrato: dinheiro em número, e o saldo final vem somado")
    void extrato() {
        var extrato = new ExtratoDoProfissional(ANA, "Ana", LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 30), Money.de("-120"), Money.de("640"),
                List.of(new ExtratoDoProfissional.TotalPorTipo(TipoDeLancamento.COMISSAO,
                                Money.de("900")),
                        new ExtratoDoProfissional.TotalPorTipo(TipoDeLancamento.VALE,
                                Money.de("-260"))),
                List.of(linha("Coloração 30%", "270", TipoDeLancamento.COMISSAO)));

        var resposta = ExtratosController.ExtratoResponse.de(extrato);

        assertThat(resposta.saldoAnterior()).isEqualByComparingTo("-120");
        assertThat(resposta.aReceber()).isEqualByComparingTo("640");
        // Quem adiantou mais do que ganhou atravessa o mês devendo: o saldo final é a conta que
        // o repasse usa, e é ela que a tela precisa mostrar sem refazer.
        assertThat(resposta.saldoFinal()).isEqualByComparingTo("520");
        assertThat(resposta.totais()).extracting(r -> r.valor().toPlainString())
                .containsExactly("900.0000", "-260.0000");
        assertThat(resposta.linhas().getFirst().valor()).isEqualByComparingTo("270");
    }

    @Test
    @DisplayName("extrato: a linha carrega o id do razão, que é por onde se estorna")
    void extrato_leva_o_id_do_razao() {
        var linha = linha("Coloração 30%", "270", TipoDeLancamento.COMISSAO);
        var extrato = new ExtratoDoProfissional(ANA, "Ana", LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 30), Money.ZERO, Money.de("270"), List.of(),
                List.of(linha));

        assertThat(ExtratosController.ExtratoResponse.de(extrato).linhas().getFirst()
                .lancamentoId()).isEqualTo(linha.lancamentoId());
    }

    @Test
    @DisplayName("vale: o valor sai positivo e em número — no extrato é que ele aparece com menos")
    void vale() {
        var vale = new Vale(UUID.randomUUID(), ANA, Money.de("260"), FormaDoVale.DINHEIRO,
                LocalDate.of(2026, 9, 12), "adiantamento do dia 10", false, Instant.EPOCH);

        var resposta = ValesController.ValeResponse.de(vale, "Ana");

        assertThat(resposta.valor()).isEqualByComparingTo("260");
        assertThat(resposta.profissionalNome()).isEqualTo("Ana");
        assertThat(resposta.cancelado()).isFalse();
    }

    @Test
    @DisplayName("prévia: total, quantos recebem e o saldo final de cada um, já calculados")
    void previa() {
        // `quantosRecebem` e `saldoFinal` são derivados, e são justamente os números da frase de
        // confirmação (R-UX-05) e da linha de quem deve ao salão.
        var previa = new PreviaDoFechamento(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                Money.de("520"), "hash-da-previa",
                List.of(new PreviaDoFechamento.LinhaDaPrevia(ANA, Money.de("-120"),
                                Money.de("640"), Money.de("520")),
                        new PreviaDoFechamento.LinhaDaPrevia(UUID.randomUUID(), Money.de("-80"),
                                Money.de("30"), Money.ZERO)));

        var resposta = previaResponseDe(previa);

        assertThat(resposta.totalARepassar()).isEqualByComparingTo("520");
        assertThat(resposta.quantosRecebem()).isEqualTo(1);
        assertThat(resposta.versao()).isEqualTo("hash-da-previa");
        assertThat(resposta.linhas().getFirst().saldoFinal()).isEqualByComparingTo("520");
        // Quem deve ao salão aparece: repasse zero e saldo negativo. Sumir com a linha faria a
        // conferência não fechar.
        assertThat(resposta.linhas().getLast().aRepassar()).isEqualByComparingTo("0");
        assertThat(resposta.linhas().getLast().saldoFinal()).isEqualByComparingTo("-50");
    }

    @Test
    @DisplayName("fechamento: o saldo a transportar vem pronto, e não é coluna")
    void fechamento() {
        var f = new FechamentoDoProfissional(UUID.randomUUID(), ANA, LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 30), Money.de("-120"), Money.de("640"), Money.de("520"),
                Instant.EPOCH, null);

        var resposta = FechamentosController.FechamentoResponse.de(f, "Ana");

        assertThat(resposta.valorRepassado()).isEqualByComparingTo("520");
        assertThat(resposta.saldoATransportar()).isEqualByComparingTo("0");
        assertThat(resposta.estornadoEm()).isNull();
    }

    private FechamentosController.PreviaResponse previaResponseDe(PreviaDoFechamento previa) {
        Map<UUID, String> nomes = previa.linhas().stream()
                .collect(java.util.stream.Collectors.toMap(
                        PreviaDoFechamento.LinhaDaPrevia::profissionalId, l -> "Alguém"));
        return FechamentosController.PreviaResponse.de(previa, nomes);
    }

    private ExtratoDoProfissional.LinhaDoExtrato linha(String descricao, String valor,
                                                       TipoDeLancamento tipo) {
        return new ExtratoDoProfissional.LinhaDoExtrato(LocalDate.of(2026, 9, 10), tipo,
                descricao, Money.de(valor), "COMANDA", UUID.randomUUID(), UUID.randomUUID());
    }
}
