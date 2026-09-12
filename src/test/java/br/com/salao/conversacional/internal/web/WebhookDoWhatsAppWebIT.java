package br.com.salao.conversacional.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.shared.tenant.AbstractPostgresIT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * RT-CNV-001 — o webhook no fio.
 *
 * <h2>O que só este teste prova</h2>
 *
 * <p>Que a rota está de fato <strong>aberta na cadeia de segurança</strong>. Todo o resto do
 * recebimento pode estar correto e a Meta receber 401 em cada entrega, porque a lista de
 * {@code permitAll} vive noutro módulo e a regra de fundo de {@code SegurancaConfig} é
 * "tudo o mais fechado". Um teste de unidade do controller nunca veria isso.
 *
 * <p>E prova o contrário na outra metade: o cadastro do canal, que é operação de gente, continua
 * exigindo autenticação.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebhookDoWhatsAppWebIT extends AbstractPostgresIT {

    private static final String SEGREDO = "segredo-do-app-da-meta-para-o-teste";
    private static final String TOKEN = "token-de-verificacao-do-teste";

    @DynamicPropertySource
    static void credenciais(DynamicPropertyRegistry registro) {
        registro.add("app.whatsapp.app-secret", () -> SEGREDO);
        registro.add("app.whatsapp.token-de-verificacao", () -> TOKEN);
    }

    @Value("${local.server.port}")
    private int porta;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    @DisplayName("POST assinado passa pela cadeia de segurança e responde 200")
    void rota_publica() throws Exception {
        // Sem token, sem cookie, sem tenant — e 200. É o que a Meta precisa, e é a única rota do
        // sistema assim.
        byte[] corpo = """
                {"object":"whatsapp_business_account","entry":[{"changes":[{"field":"messages",
                  "value":{"metadata":{"phone_number_id":"NUMERO_SEM_DONO"},
                  "messages":[{"from":"5511988887777","id":"wamid.WEB","timestamp":"1757000000",
                               "type":"text","text":{"body":"oi"}}]}}]}]}
                """.getBytes(StandardCharsets.UTF_8);

        var resposta = postar(corpo, "sha256=" + hmac(SEGREDO, corpo));

        assertThat(resposta.statusCode())
                .as("401 aqui significa que o permitAll de /webhooks/whatsapp não existe")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("POST sem assinatura válida responde 403 — pública não é aberta")
    void assinatura_invalida_no_fio() throws Exception {
        byte[] corpo = "{}".getBytes(StandardCharsets.UTF_8);

        assertThat(postar(corpo, "sha256=" + hmac("segredo-errado", corpo)).statusCode())
                .isEqualTo(403);
        assertThat(postar(corpo, null).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("o handshake devolve o desafio em texto puro, e só com o token certo")
    void handshake_no_fio() throws Exception {
        var aceito = http.send(HttpRequest.newBuilder()
                .uri(URI.create(url() + "?hub.mode=subscribe&hub.verify_token=" + TOKEN
                        + "&hub.challenge=1158201444"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(aceito.statusCode()).isEqualTo(200);
        assertThat(aceito.body()).isEqualTo("1158201444");
        assertThat(aceito.headers().firstValue("Content-Type"))
                .hasValueSatisfying(tipo -> assertThat(tipo).startsWith("text/plain"));

        var recusado = http.send(HttpRequest.newBuilder()
                .uri(URI.create(url() + "?hub.mode=subscribe&hub.verify_token=errado"
                        + "&hub.challenge=1158201444"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(recusado.statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("o cadastro do canal continua FECHADO: a outra metade é operação de gente")
    void cadastro_exige_autenticacao() throws Exception {
        var resposta = http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + porta + "/api/v1/canais/whatsapp"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("""
                        {"phoneNumberId":"9999","numeroExibicao":"5511999990000"}"""))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(resposta.statusCode()).isEqualTo(401);
    }

    private String url() {
        return "http://localhost:" + porta + "/webhooks/whatsapp";
    }

    private HttpResponse<String> postar(byte[] corpo, String assinatura) throws Exception {
        var pedido = HttpRequest.newBuilder()
                .uri(URI.create(url()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(corpo));
        if (assinatura != null) {
            pedido.header("X-Hub-Signature-256", assinatura);
        }
        return http.send(pedido.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String hmac(String segredo, byte[] corpo) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(corpo));
    }
}
