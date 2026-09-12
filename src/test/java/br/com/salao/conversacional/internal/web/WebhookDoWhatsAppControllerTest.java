package br.com.salao.conversacional.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import br.com.salao.conversacional.internal.application.ReceberLoteDoWhatsAppUseCase;
import br.com.salao.conversacional.internal.domain.AssinaturaDoWebhook;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * RT-CNV-001 — a borda do webhook, sem servidor.
 *
 * <p>Unitário de propósito: o que está sob teste é a ORDEM das verificações e o código de
 * resposta de cada recusa. Subir contexto para isso custaria meio minuto por asserção e não
 * provaria nada a mais.
 */
class WebhookDoWhatsAppControllerTest {

    private static final String SEGREDO = "segredo-do-app";
    private static final String TOKEN = "token-de-verificacao";

    private final ReceberLoteDoWhatsAppUseCase receber = mock(ReceberLoteDoWhatsAppUseCase.class);

    private WebhookDoWhatsAppController controller(String segredo) {
        return new WebhookDoWhatsAppController(new AssinaturaDoWebhook(segredo), TOKEN,
                receber, JsonMapper.builder().build());
    }

    private MockHttpServletRequest post(byte[] corpo, String assinatura) {
        var requisicao = new MockHttpServletRequest("POST", "/webhooks/whatsapp");
        requisicao.setContent(corpo);
        if (assinatura != null) {
            requisicao.addHeader("X-Hub-Signature-256", assinatura);
        }
        return requisicao;
    }

    @Test
    @DisplayName("sem segredo configurado devolve 503 e NÃO chega ao caso de uso")
    void falha_fechada() throws Exception {
        /*
         * O modo de falha que importa: deploy com a variável esquecida. A alternativa — aceitar
         * porque não há como validar — daria a qualquer um na internet permissão de gravar
         * mensagem em nome de qualquer salão.
         *
         * 503 e não 403: a recusa é nossa, não de quem chamou.
         */
        byte[] corpo = "{}".getBytes(StandardCharsets.UTF_8);

        var resposta = controller("").receber(post(corpo, "sha256=" + hmac(SEGREDO, corpo)));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(receber, never()).executar(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("assinatura inválida devolve 403 e o JSON NUNCA é interpretado")
    void assinatura_invalida() throws Exception {
        // O parser é a maior superfície de ataque desta rota. Ele só deve ver bytes assinados.
        byte[] corpo = "{\"entry\":[]}".getBytes(StandardCharsets.UTF_8);

        var resposta = controller(SEGREDO).receber(post(corpo, "sha256=" + hmac("errado", corpo)));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(receber, never()).executar(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("sem cabeçalho de assinatura devolve 403, não 200")
    void sem_assinatura() throws Exception {
        // 200 aqui tornaria requisição forjada indistinguível de legítima no log de acesso.
        byte[] corpo = "{}".getBytes(StandardCharsets.UTF_8);

        assertThat(controller(SEGREDO).receber(post(corpo, null)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("corpo acima do teto devolve 413 SEM calcular HMAC sobre ele")
    void corpo_grande() throws Exception {
        /*
         * A rota é pública e o HMAC percorre o corpo inteiro: sem teto, um POST de 2 GB é CPU e
         * memória de graça para quem nem tem o segredo. Webhook da Cloud API tem kilobytes — a
         * mídia vem como id para baixar depois, não no corpo.
         */
        byte[] enorme = new byte[512 * 1024 + 1];
        java.util.Arrays.fill(enorme, (byte) 'x');

        var resposta = controller(SEGREDO).receber(post(enorme, "sha256=" + hmac(SEGREDO, enorme)));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        verify(receber, never()).executar(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("um corpo grande, mas dentro do teto, passa")
    void corpo_no_limite() throws Exception {
        // O teto é teto, não estreiteza: um lote com muitas mensagens tem de caber.
        byte[] grande = ("{\"padding\":\"" + "x".repeat(400 * 1024) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        // O dublê devolve null por padrão, e o controller lê o resumo para montar o log.
        org.mockito.Mockito.when(receber.executar(ArgumentMatchers.any()))
                .thenReturn(new ReceberLoteDoWhatsAppUseCase.Resumo(0, 0, 0, 0));

        var resposta = controller(SEGREDO).receber(post(grande, "sha256=" + hmac(SEGREDO, grande)));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(receber).executar(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("JSON ilegível já assinado devolve 200: reenviar não conserta")
    void json_ilegivel() throws Exception {
        // Assinado pela Meta e ilegível é defeito do provedor. 500 faria a Meta reenviar o mesmo
        // lote quebrado para sempre, e cada reenvio traz o lote inteiro.
        byte[] corpo = "{isto nao e json".getBytes(StandardCharsets.UTF_8);

        var resposta = controller(SEGREDO).receber(post(corpo, "sha256=" + hmac(SEGREDO, corpo)));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(receber, never()).executar(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("o handshake devolve o desafio em texto puro")
    void handshake() {
        // JSON aqui faz a verificação da Meta falhar sem dizer por quê: o corpo tem de ser
        // exatamente o desafio.
        var resposta = controller(SEGREDO).verificar("subscribe", TOKEN, "1158201444");

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resposta.getBody()).isEqualTo("1158201444");
    }

    @Test
    @DisplayName("handshake com token errado, modo errado ou sem desafio devolve 403")
    void handshake_recusado() {
        var controller = controller(SEGREDO);

        assertThat(controller.verificar("subscribe", "token-errado", "123").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.verificar("unsubscribe", TOKEN, "123").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.verificar("subscribe", TOKEN, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.verificar(null, null, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private static String hmac(String segredo, byte[] corpo) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(corpo));
    }
}
