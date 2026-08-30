package br.com.salao.shared.rede;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.shared.tenant.AbstractPostgresIT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** RT-INF-011 — limite de taxa, CORS e cabeçalhos de segurança. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LimiteDeTaxaWebIT extends AbstractPostgresIT {

    private static final int LIMITE_AUTH = 5;

    @DynamicPropertySource
    static void limitesBaixos(DynamicPropertyRegistry registro) {
        registro.add("app.rede.limite-autenticacao", () -> LIMITE_AUTH);
        registro.add("app.rede.limite-geral", () -> "50");
        registro.add("app.cors.origens", () -> "http://localhost:5173");
    }

    @Value("${local.server.port}")
    private int porta;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    @DisplayName("password spraying é barrado pelo limite por IP, não pelo bloqueio por conta")
    void limite_barra_tentativas_repetidas() throws Exception {
        // Mil e-mails diferentes acumulam UMA falha em cada conta e nunca disparam o bloqueio
        // progressivo (RT-IAM-002). É este limite que os detém.
        int recusadas = 0;
        for (int i = 0; i < LIMITE_AUTH + 3; i++) {
            var r = login("alvo" + i + "@salao.test");
            if (r.statusCode() == 429) recusadas++;
        }

        assertThat(recusadas).as("as tentativas além do limite são recusadas").isPositive();
    }

    @Test
    @DisplayName("a recusa diz quando tentar de novo e traz o código do catálogo")
    void recusa_e_acionavel() throws Exception {
        HttpResponse<String> ultima = null;
        for (int i = 0; i < LIMITE_AUTH + 3; i++) {
            ultima = login("alvo@salao.test");
        }

        assertThat(ultima.statusCode()).isEqualTo(429);
        assertThat(ultima.headers().firstValue("Retry-After"))
                .as("sem Retry-After, o cliente só pode adivinhar quando voltar")
                .isPresent();
        assertThat(ultima.body()).contains("ER-INF-LIMITE_DE_REQUISICOES");
    }

    @Test
    @DisplayName("as faixas de autenticação e de API são independentes")
    void faixas_sao_separadas() throws Exception {
        // Apertar a API geral no mesmo balde do login transformaria abrir a agenda — que
        // dispara várias chamadas — em erro para quem só errou a senha uma vez.
        for (int i = 0; i < LIMITE_AUTH + 3; i++) {
            login("alvo@salao.test");
        }

        var outra = get("/api/v1/me/capabilities");

        assertThat(outra.statusCode())
                .as("401 por falta de token, não 429: o balde é outro")
                .isIn(401, 403);
    }

    @Test
    @DisplayName("CORS responde só para a origem declarada")
    void cors_e_explicito() throws Exception {
        var permitida = comOrigem("http://localhost:5173");
        var outra = comOrigem("https://sitedeoutrapessoa.example");

        assertThat(permitida.headers().firstValue("Access-Control-Allow-Origin"))
                .contains("http://localhost:5173");
        assertThat(outra.headers().firstValue("Access-Control-Allow-Origin"))
                .as("origem não declarada não recebe permissão — nunca curinga")
                .isEmpty();
    }

    @Test
    @DisplayName("os cabeçalhos de segurança acompanham a resposta")
    void cabecalhos_de_seguranca() throws Exception {
        var resposta = get("/api/v1/me/capabilities");

        assertThat(resposta.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(resposta.headers().firstValue("X-Frame-Options")).contains("DENY");
        assertThat(resposta.headers().firstValue("Referrer-Policy")).isPresent();
        assertThat(resposta.headers().firstValue("Content-Security-Policy"))
                .as("a API serve JSON e nada mais")
                .hasValueSatisfying(v -> assertThat(v).contains("default-src 'none'"));
    }

    private HttpResponse<String> login(String email) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + porta + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"" + email + "\",\"senha\":\"senha-qualquer-123\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String caminho) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + porta + caminho))
                .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> comOrigem(String origem) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + porta + "/api/v1/me/capabilities"))
                .header("Origin", origem)
                .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
