package br.com.salao.conversacional.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RT-CNV-006 / RN-CNV-019 — o id do botão de aceite, lido como entrada não confiável. */
class BotaoDeAceiteTest {

    private final UUID intencao = UUID.fromString("7b0c6f0e-2f7d-4a8e-9c1d-3b2a1f0e9d8c");

    @Test
    @DisplayName("o id vai e volta: intenção, token e decisão")
    void ida_e_volta() {
        String token = BotaoDeAceite.novoToken(new Random(42));
        var aceitar = new BotaoDeAceite(intencao, token, true);
        var recusar = new BotaoDeAceite(intencao, token, false);

        assertThat(BotaoDeAceite.de(aceitar.idDoBotao())).contains(aceitar);
        assertThat(BotaoDeAceite.de(recusar.idDoBotao())).contains(recusar);
        assertThat(aceitar.idDoBotao()).endsWith(":sim");
        assertThat(recusar.idDoBotao()).endsWith(":nao");
    }

    @Test
    @DisplayName("o token tem 24 caracteres de base64 de URL, e dois nunca saem iguais")
    void token() {
        var aleatorio = new Random(7);
        String um = BotaoDeAceite.novoToken(aleatorio);
        String outro = BotaoDeAceite.novoToken(aleatorio);

        assertThat(um).matches("[A-Za-z0-9_-]{24}");
        assertThat(outro).isNotEqualTo(um);
    }

    @Test
    @DisplayName("id fora do formato não é botão de aceite")
    void nao_reconhece() {
        String token = "a".repeat(24);
        for (String id : List.of("", "aceite", "sim",
                "aceite:" + intencao + ":curto:sim",
                "aceite:nao-e-uuid:" + token + ":sim",
                "aceite:" + intencao + ":" + token + ":talvez",
                "aceite:" + intencao + ":" + token + ":sim:extra",
                " aceite:" + intencao + ":" + token + ":sim",
                "aceite:" + intencao.toString().toUpperCase() + ":" + token + ":sim",
                "aceite:" + intencao + ":" + "a".repeat(65) + ":sim")) {
            assertThat(BotaoDeAceite.de(id)).as(id).isEmpty();
        }
        assertThat(BotaoDeAceite.de(null)).isEmpty();
    }
}
