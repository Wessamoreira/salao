package br.com.salao.financeiro.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.financeiro.api.FormaDePagamento;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RT-FIN-010 — casar pagamento com venda: o que prova, e o que só parece provar. */
class CasadorTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final Duration JANELA = Duration.ofHours(2);

    private static Instant as(String hora) {
        return LocalDateTime.parse("2026-09-18T" + hora).atZone(SP).toInstant();
    }

    private static Casador.Pagamento pagamento(String valor, FormaDePagamento forma, String hora) {
        return new Casador.Pagamento(UUID.randomUUID(), new BigDecimal(valor), forma, as(hora), null,
                null, null, 1);
    }

    private static Casador.Venda venda(String valor, ProdutoDoCartao produto, String hora) {
        return new Casador.Venda(UUID.randomUUID(), new BigDecimal(valor), produto, as(hora), true,
                null, null, null, 1);
    }

    private static List<Casador.Par> casar(List<Casador.Pagamento> pagamentos,
                                           List<Casador.Venda> vendas) {
        return Casador.casar(pagamentos, vendas, JANELA, SP);
    }

    @Test
    @DisplayName("valor exato na janela casa; valor e forma iguais não divergem")
    void valor_e_hora() {
        var p = pagamento("100.00", FormaDePagamento.CREDITO, "14:00");
        var v = venda("100", ProdutoDoCartao.CREDITO, "14:05");

        assertThat(casar(List.of(p), List.of(v))).singleElement().satisfies(par -> {
            assertThat(par.pagamentoId()).isEqualTo(p.id());
            assertThat(par.vendaId()).isEqualTo(v.id());
            assertThat(par.criterio()).isEqualTo(CriterioDoCasamento.VALOR_E_HORA);
            assertThat(par.divergencias()).isEmpty();
        });
    }

    @Test
    @DisplayName("duas horas é o limite: dentro casa, um minuto fora não")
    void janela() {
        var dentro = pagamento("70", FormaDePagamento.CREDITO, "14:00");
        var fora = pagamento("50", FormaDePagamento.CREDITO, "14:00");

        var pares = casar(List.of(dentro, fora), List.of(venda("70", ProdutoDoCartao.CREDITO, "16:00"),
                venda("50", ProdutoDoCartao.CREDITO, "16:01")));

        assertThat(pares).singleElement().satisfies(par -> assertThat(par.pagamentoId()).isEqualTo(dentro.id()));
    }

    @Test
    @DisplayName("venda sem hora no arquivo casa no mesmo dia do salão, e não no seguinte")
    void sem_hora_mesmo_dia() {
        var p = pagamento("80", FormaDePagamento.DEBITO, "22:30");
        var mesmoDia = new Casador.Venda(UUID.randomUUID(), new BigDecimal("80"), ProdutoDoCartao.DEBITO,
                as("00:00"), false, null, null, null, 1);
        var diaSeguinte = new Casador.Venda(UUID.randomUUID(), new BigDecimal("80"),
                ProdutoDoCartao.DEBITO, as("00:00").plus(Duration.ofDays(1)), false, null, null, null, 1);

        assertThat(casar(List.of(p), List.of(diaSeguinte))).isEmpty();
        assertThat(casar(List.of(p), List.of(mesmoDia))).hasSize(1);
    }

    @Test
    @DisplayName("empate de valor casa pela ordem de horário: cada pagamento com a venda mais próxima")
    void empate_pela_ordem() {
        var cedo = pagamento("50", FormaDePagamento.CREDITO, "14:00");
        var tarde = pagamento("50", FormaDePagamento.CREDITO, "14:30");
        var vendaCedo = venda("50", ProdutoDoCartao.CREDITO, "14:02");
        var vendaTarde = venda("50", ProdutoDoCartao.CREDITO, "14:31");

        var pares = casar(List.of(tarde, cedo), List.of(vendaTarde, vendaCedo));

        assertThat(pares).extracting(Casador.Par::pagamentoId, Casador.Par::vendaId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(cedo.id(), vendaCedo.id()),
                        org.assertj.core.groups.Tuple.tuple(tarde.id(), vendaTarde.id()));
    }

    @Test
    @DisplayName("a distância vem antes da forma: o débito de um minuto antes, e não o crédito de uma hora depois")
    void distancia_antes_da_forma() {
        var p = pagamento("60", FormaDePagamento.CREDITO, "10:00");
        var debitoPerto = venda("60", ProdutoDoCartao.DEBITO, "10:01");
        var creditoLonge = venda("60", ProdutoDoCartao.CREDITO, "11:00");

        assertThat(casar(List.of(p), List.of(creditoLonge, debitoPerto))).singleElement()
                .satisfies(par -> {
                    assertThat(par.vendaId()).isEqualTo(debitoPerto.id());
                    assertThat(par.divergencias()).containsExactly(Divergencia.FORMA);
                });
    }

    @Test
    @DisplayName("na mesma distância, a mesma forma primeiro")
    void mesma_distancia_mesma_forma() {
        var p = pagamento("60", FormaDePagamento.CREDITO, "10:00");
        var debito = venda("60", ProdutoDoCartao.DEBITO, "10:05");
        var credito = venda("60", ProdutoDoCartao.CREDITO, "09:55");

        assertThat(casar(List.of(p), List.of(debito, credito))).singleElement()
                .satisfies(par -> assertThat(par.vendaId()).isEqualTo(credito.id()));
    }

    @Test
    @DisplayName("NSU único é a prova mais forte: casa fora da janela, e diz se o valor difere")
    void nsu() {
        var p = new Casador.Pagamento(UUID.randomUUID(), new BigDecimal("100"),
                FormaDePagamento.CREDITO, as("10:00"), "000123", null, null, 1);
        var v = new Casador.Venda(UUID.randomUUID(), new BigDecimal("90"), ProdutoDoCartao.CREDITO,
                as("18:00"), true, "123", null, null, 1);

        assertThat(casar(List.of(p), List.of(v))).singleElement().satisfies(par -> {
            assertThat(par.criterio()).isEqualTo(CriterioDoCasamento.NSU);
            assertThat(par.divergencias()).containsExactly(Divergencia.VALOR);
        });
    }

    @Test
    @DisplayName("NSU repetido não prova nada: cai para valor e hora")
    void nsu_repetido() {
        var p = new Casador.Pagamento(UUID.randomUUID(), new BigDecimal("100"),
                FormaDePagamento.CREDITO, as("10:00"), "555", null, null, 1);
        var longe = new Casador.Venda(UUID.randomUUID(), new BigDecimal("90"), ProdutoDoCartao.CREDITO,
                as("18:00"), true, "555", null, null, 1);
        var perto = new Casador.Venda(UUID.randomUUID(), new BigDecimal("100"), ProdutoDoCartao.CREDITO,
                as("10:03"), true, "555", null, null, 1);

        assertThat(casar(List.of(p), List.of(longe, perto))).singleElement().satisfies(par -> {
            assertThat(par.vendaId()).isEqualTo(perto.id());
            assertThat(par.criterio()).isEqualTo(CriterioDoCasamento.VALOR_E_HORA);
        });
    }

    @Test
    @DisplayName("autorização igual só prova dentro da janela: o código se repete com o tempo")
    void autorizacao() {
        var p = new Casador.Pagamento(UUID.randomUUID(), new BigDecimal("100"),
                FormaDePagamento.CREDITO, as("10:00"), null, "ab12cd", null, 1);
        var longe = new Casador.Venda(UUID.randomUUID(), new BigDecimal("40"), ProdutoDoCartao.CREDITO,
                as("15:00"), true, null, "AB12CD", null, 1);
        var perto = new Casador.Venda(UUID.randomUUID(), new BigDecimal("40"), ProdutoDoCartao.CREDITO,
                as("10:30"), true, null, "AB12CD", null, 1);

        assertThat(casar(List.of(p), List.of(longe))).isEmpty();
        assertThat(casar(List.of(p), List.of(perto))).singleElement().satisfies(par -> {
            assertThat(par.criterio()).isEqualTo(CriterioDoCasamento.AUTORIZACAO);
            assertThat(par.divergencias()).containsExactly(Divergencia.VALOR);
        });
    }

    @Test
    @DisplayName("bandeira e parcelas só divergem quando a recepção informou o dado")
    void so_o_informado() {
        var semInformar = pagamento("300", FormaDePagamento.CREDITO, "10:00");
        var parcelada = new Casador.Venda(UUID.randomUUID(), new BigDecimal("300"),
                ProdutoDoCartao.CREDITO, as("10:02"), true, null, null, "VISA", 3);
        var informado = new Casador.Pagamento(UUID.randomUUID(), new BigDecimal("300"),
                FormaDePagamento.CREDITO, as("10:00"), null, null, "mastercard", 2);

        assertThat(casar(List.of(semInformar), List.of(parcelada))).singleElement()
                .satisfies(par -> assertThat(par.divergencias()).isEmpty());
        assertThat(casar(List.of(informado), List.of(parcelada))).singleElement()
                .satisfies(par -> assertThat(par.divergencias())
                        .containsExactly(Divergencia.BANDEIRA, Divergencia.PARCELAS));
    }

    @Test
    @DisplayName("venda sem produto, ou \"outro\", não diverge pela forma")
    void produto_desconhecido() {
        var p = pagamento("20", FormaDePagamento.DEBITO, "10:00");

        assertThat(casar(List.of(p), List.of(venda("20", null, "10:01")))).singleElement()
                .satisfies(par -> assertThat(par.divergencias()).isEmpty());
        assertThat(casar(List.of(p), List.of(venda("20", ProdutoDoCartao.OUTRO, "10:01"))))
                .singleElement().satisfies(par -> assertThat(par.divergencias()).isEmpty());
    }

    @Test
    @DisplayName("cada lado casa uma vez: três pagamentos iguais e duas vendas deixam um sem par")
    void cada_lado_uma_vez() {
        var pagamentos = List.of(pagamento("50", FormaDePagamento.CREDITO, "10:00"),
                pagamento("50", FormaDePagamento.CREDITO, "10:10"),
                pagamento("50", FormaDePagamento.CREDITO, "10:20"));
        var vendas = List.of(venda("50", ProdutoDoCartao.CREDITO, "10:01"),
                venda("50", ProdutoDoCartao.CREDITO, "10:11"));

        var pares = casar(pagamentos, vendas);

        assertThat(pares).hasSize(2);
        assertThat(pares).extracting(Casador.Par::pagamentoId).doesNotHaveDuplicates()
                .doesNotContain(pagamentos.get(2).id());
    }

    @Test
    @DisplayName("a mesma entrada, em qualquer ordem, dá os mesmos pares")
    void deterministico() {
        var pagamentos = new ArrayList<Casador.Pagamento>();
        var vendas = new ArrayList<Casador.Venda>();
        for (int i = 0; i < 12; i++) {
            pagamentos.add(pagamento("50", FormaDePagamento.CREDITO, String.format("1%d:00", i % 6)));
            vendas.add(venda("50", i % 2 == 0 ? ProdutoDoCartao.CREDITO : ProdutoDoCartao.DEBITO,
                    String.format("1%d:0%d", i % 6, i % 9)));
        }
        var esperado = casar(pagamentos, vendas);

        var embaralhadosP = new ArrayList<>(pagamentos);
        var embaralhadasV = new ArrayList<>(vendas);
        Collections.shuffle(embaralhadosP, new Random(7));
        Collections.shuffle(embaralhadasV, new Random(11));

        assertThat(casar(embaralhadosP, embaralhadasV)).containsExactlyInAnyOrderElementsOf(esperado);
    }

    @Test
    @DisplayName("\"000123\", \"123\" e \" 1-2-3 \" são o mesmo código")
    void codigo() {
        assertThat(Casador.codigo("000123")).isEqualTo("123");
        assertThat(Casador.codigo(" 1-2-3 ")).isEqualTo("123");
        assertThat(Casador.codigo("ab12cd")).isEqualTo("AB12CD");
        assertThat(Casador.codigo("  ")).isNull();
    }
}
