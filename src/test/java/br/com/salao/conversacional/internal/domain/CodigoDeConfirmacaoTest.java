package br.com.salao.conversacional.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** RT-CNV-005 / RN-CNV-015 — a confirmação é reconhecida, nunca interpretada. */
class CodigoDeConfirmacaoTest {

    @ParameterizedTest
    @ValueSource(strings = { "SIM 4821", "sim 4821", "Sim, 4821", "SIM4821", "  sim 4821.  ",
            "SIM: 4821!", "sim - 4821", "SIM\n4821" })
    @DisplayName("reconhece a confirmação escrita de vários jeitos")
    void reconhece(String mensagem) {
        assertThat(CodigoDeConfirmacao.codigoEm(mensagem)).contains("4821");
    }

    @ParameterizedTest
    @ValueSource(strings = { "sim", "4821", "sim 482", "sim 48210", "SIM 48 21",
            "sim 4821 e cancela o resto", "pode marcar, sim 4821", "não 4821", "simples 4821",
            "confirma 4821", "" })
    @DisplayName("mensagem que não é SÓ a confirmação não é confirmação")
    void nao_reconhece(String mensagem) {
        // "sim 4821 e cancela o resto" é o caso que importa: com qualquer coisa a mais, a mensagem
        // vai para o modelo — que não tem como gravar.
        assertThat(CodigoDeConfirmacao.codigoEm(mensagem)).isEmpty();
    }

    @Test
    @DisplayName("mensagem nula não é confirmação")
    void nula() {
        assertThat(CodigoDeConfirmacao.codigoEm(null)).isEmpty();
    }

    @Test
    @DisplayName("o sorteio não repete código recente do telefone, e completa com zeros")
    void sorteio() {
        var valores = new ArrayDeque<>(List.of(4821, 4821, 7));
        RandomGenerator roteirizado = new RandomGenerator() {
            @Override public long nextLong() { throw new UnsupportedOperationException(); }
            @Override public int nextInt(int limite) { return valores.poll(); }
        };

        assertThat(CodigoDeConfirmacao.sortear(roteirizado, Set.of("4821"))).isEqualTo("0007");
    }

    @Test
    @DisplayName("o pedido de confirmação traz o resumo, o código e até quando vale")
    void pedido() {
        String pedido = CodigoDeConfirmacao.pedido("Corte para Marilda com Ana, sábado, 20/09, "
                + "das 13:00 às 13:30", "4821", LocalTime.of(10, 42));

        assertThat(pedido).contains("Corte para Marilda com Ana").contains("responda SIM 4821")
                .contains("até 10:42");
        assertThat(CodigoDeConfirmacao.codigoEm(pedido))
                .as("o próprio pedido não é uma confirmação").isEmpty();
    }

    @Test
    @DisplayName("RT-CNV-006: com aceite a pedir, o pedido diz que o SIM ainda não marca")
    void pedido_com_aceite() {
        String pedido = CodigoDeConfirmacao.pedido("Corte para Marilda com Ana, sábado, 20/09, "
                + "das 19:00 às 19:30", "4821", LocalTime.of(10, 42), "Ana", "fora da jornada");

        assertThat(pedido).contains("responda SIM 4821")
                .endsWith("Depois do seu SIM, Ana ainda precisa aceitar (fora da jornada).");
        assertThat(CodigoDeConfirmacao.codigoEm(pedido)).isEmpty();
    }
}
