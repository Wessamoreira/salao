package br.com.salao.conversacional.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RT-CNV-001 / RN-CNV-001 — o que separa a Meta de qualquer um na internet. */
class AssinaturaDoWebhookTest {

    private static final String SEGREDO = "segredo-do-app-da-meta";
    private final AssinaturaDoWebhook assinatura = new AssinaturaDoWebhook(SEGREDO);

    @Test
    @DisplayName("assinatura correta confere")
    void confere() {
        byte[] corpo = "{\"object\":\"whatsapp_business_account\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(assinatura.confere(corpo, "sha256=" + hmac(SEGREDO, corpo))).isTrue();
    }

    @Test
    @DisplayName("um byte trocado no corpo invalida a assinatura")
    void corpo_adulterado() {
        // É o ataque que a assinatura existe para deter: interceptar o webhook legítimo e trocar
        // o telefone da cliente pelo do atacante antes de repassá-lo.
        byte[] original = "{\"from\":\"5511999990000\"}".getBytes(StandardCharsets.UTF_8);
        String valida = hmac(SEGREDO, original);
        byte[] adulterado = "{\"from\":\"5511999990001\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(assinatura.confere(adulterado, "sha256=" + valida)).isFalse();
    }

    @Test
    @DisplayName("assinatura de outro segredo não confere")
    void outro_segredo() {
        byte[] corpo = "{}".getBytes(StandardCharsets.UTF_8);

        assertThat(assinatura.confere(corpo, "sha256=" + hmac("outro-segredo", corpo))).isFalse();
    }

    @Test
    @DisplayName("sem segredo configurado, NADA confere")
    void falha_fechada() {
        // O modo de falha que importa: deploy com a variável esquecida. Aceitar por não ter como
        // validar transformaria isso em permissão para gravar mensagem em nome de qualquer salão.
        var semSegredo = new AssinaturaDoWebhook("  ");
        byte[] corpo = "{}".getBytes(StandardCharsets.UTF_8);

        assertThat(semSegredo.configurado()).isFalse();
        assertThat(semSegredo.confere(corpo, "sha256=" + hmac("", corpo))).isFalse();
        assertThat(semSegredo.confere(corpo, "sha256=")).isFalse();
    }

    @Test
    @DisplayName("cabeçalho ausente, sem prefixo ou com hex torto é só assinatura que não confere")
    void cabecalho_torto() {
        // Nunca exceção: um throw aqui viraria 500, e 500 numa rota pública é sinal para quem
        // está sondando. Cabeçalho malformado é simplesmente falso.
        byte[] corpo = "{}".getBytes(StandardCharsets.UTF_8);

        assertThat(assinatura.confere(corpo, null)).isFalse();
        assertThat(assinatura.confere(corpo, "")).isFalse();
        assertThat(assinatura.confere(corpo, hmac(SEGREDO, corpo))).isFalse();
        assertThat(assinatura.confere(corpo, "sha256=nao-e-hex")).isFalse();
        assertThat(assinatura.confere(corpo, "sha256=abc")).isFalse();
        assertThat(assinatura.confere(null, "sha256=abcd")).isFalse();
    }

    @Test
    @DisplayName("o prefixo sha256= é aceito em qualquer caixa")
    void prefixo_sem_caixa() {
        // A Meta não prometeu a caixa do prefixo. Depender dela seria acoplar a um detalhe que
        // pode mudar sem aviso e derrubar o recebimento inteiro.
        byte[] corpo = "{}".getBytes(StandardCharsets.UTF_8);

        assertThat(assinatura.confere(corpo, "SHA256=" + hmac(SEGREDO, corpo))).isTrue();
    }

    @Test
    @DisplayName("assinatura em hex maiúsculo confere")
    void hex_maiusculo() {
        byte[] corpo = "{}".getBytes(StandardCharsets.UTF_8);

        assertThat(assinatura.confere(corpo,
                "sha256=" + hmac(SEGREDO, corpo).toUpperCase())).isTrue();
    }

    @Test
    @DisplayName("token do handshake: confere o certo, recusa o errado e recusa o vazio")
    void token_do_handshake() {
        assertThat(AssinaturaDoWebhook.tokenConfere("token-certo", "token-certo")).isTrue();
        assertThat(AssinaturaDoWebhook.tokenConfere("token-certo", "token-errado")).isFalse();
        // Token não configurado não pode virar "qualquer coisa serve", nem "vazio serve".
        assertThat(AssinaturaDoWebhook.tokenConfere("", "")).isFalse();
        assertThat(AssinaturaDoWebhook.tokenConfere(null, "qualquer")).isFalse();
        assertThat(AssinaturaDoWebhook.tokenConfere("token-certo", null)).isFalse();
        // Prefixo certo não basta: comparação de prefixo passaria aqui.
        assertThat(AssinaturaDoWebhook.tokenConfere("token-certo", "token-cert")).isFalse();
    }

    /** O HMAC calculado do jeito independente, para o teste não usar a classe que ele testa. */
    private static String hmac(String segredo, byte[] corpo) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    segredo.isEmpty() ? new byte[1] : segredo.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(corpo));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
