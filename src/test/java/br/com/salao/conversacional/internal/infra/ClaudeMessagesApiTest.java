package br.com.salao.conversacional.internal.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.salao.conversacional.internal.application.FalhaPermanente;
import br.com.salao.conversacional.internal.application.FalhaTransitoria;
import br.com.salao.conversacional.internal.application.ModeloDeLinguagem.ChamadaDeFerramenta;
import br.com.salao.conversacional.internal.application.ModeloDeLinguagem.FerramentaOferecida;
import br.com.salao.conversacional.internal.application.ModeloDeLinguagem.Mensagem;
import br.com.salao.conversacional.internal.application.ModeloDeLinguagem.Papel;
import br.com.salao.conversacional.internal.application.ModeloDeLinguagem.Pedido;
import br.com.salao.conversacional.internal.application.ModeloDeLinguagem.ResultadoDeFerramenta;
import br.com.salao.conversacional.internal.application.ModeloDeLinguagem.Texto;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** RT-CNV-004 — a Messages API contra um servidor de verdade, do próprio JDK. */
class ClaudeMessagesApiTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private HttpServer servidor;
    private final AtomicReference<String> chave = new AtomicReference<>();
    private final AtomicReference<String> versao = new AtomicReference<>();
    private final AtomicReference<JsonNode> corpo = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> resposta = new AtomicReference<>();
    private final AtomicInteger pedidosAoDestino = new AtomicInteger();

    @BeforeEach
    void subir() throws Exception {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/v1/messages", troca -> {
            chave.set(troca.getRequestHeaders().getFirst("x-api-key"));
            versao.set(troca.getRequestHeaders().getFirst("anthropic-version"));
            corpo.set(json.readTree(troca.getRequestBody().readAllBytes()));
            if (status.get() == 302) {
                troca.getResponseHeaders().add("Location",
                        "http://127.0.0.1:" + servidor.getAddress().getPort() + "/destino");
            }
            byte[] saida = resposta.get() == null ? new byte[0]
                    : resposta.get().getBytes(StandardCharsets.UTF_8);
            troca.sendResponseHeaders(status.get(), saida.length == 0 ? -1 : saida.length);
            if (saida.length > 0) {
                troca.getResponseBody().write(saida);
            }
            troca.close();
        });
        servidor.createContext("/destino", troca -> {
            pedidosAoDestino.incrementAndGet();
            troca.sendResponseHeaders(200, -1);
            troca.close();
        });
        servidor.start();
    }

    @AfterEach
    void descer() {
        servidor.stop(0);
    }

    private ClaudeMessagesApi api() {
        return new ClaudeMessagesApi(
                URI.create("http://127.0.0.1:" + servidor.getAddress().getPort() + "/v1/messages"),
                "chave-teste", "claude-opus-5", json);
    }

    private Pedido pedidoSimples(List<FerramentaOferecida> ferramentas) {
        return new Pedido("sistema de teste", List.of(Mensagem.doUsuario("quais serviços?")),
                ferramentas, 256);
    }

    @Test
    @DisplayName("manda chave, versão, modelo e ferramentas; lê texto, pedido de ferramenta e uso")
    void ida_e_volta() {
        resposta.set("""
                {"content":[{"type":"text","text":"Vou olhar."},
                            {"type":"tool_use","id":"tu_1","name":"listar_servicos","input":{}}],
                 "stop_reason":"tool_use","usage":{"input_tokens":120,"output_tokens":30}}""");

        var saida = api().responder(pedidoSimples(List.of(new FerramentaOferecida(
                "listar_servicos", "lista", json.readTree("{\"type\":\"object\"}")))));

        assertThat(chave.get()).isEqualTo("chave-teste");
        assertThat(versao.get()).isEqualTo("2023-06-01");
        assertThat(corpo.get().path("model").asString()).isEqualTo("claude-opus-5");
        assertThat(corpo.get().path("system").asString()).isEqualTo("sistema de teste");
        assertThat(corpo.get().path("tools").get(0).path("name").asString())
                .isEqualTo("listar_servicos");
        assertThat(corpo.get().path("messages").get(0).path("role").asString()).isEqualTo("user");

        assertThat(saida.texto()).isEqualTo("Vou olhar.");
        assertThat(saida.chamadas()).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo("tu_1");
            assertThat(c.nome()).isEqualTo("listar_servicos");
        });
        assertThat(saida.tokensEntrada()).isEqualTo(120);
        assertThat(saida.tokensSaida()).isEqualTo(30);
    }

    @Test
    @DisplayName("pedido de ferramenta e resultado com erro são serializados no formato da API")
    void resultado_de_ferramenta() {
        resposta.set("""
                {"content":[{"type":"text","text":"ok"}],"usage":{"input_tokens":1,"output_tokens":1}}""");
        var mensagens = List.of(
                Mensagem.doUsuario("oi"),
                new Mensagem(Papel.ASSISTENTE, List.of(new ChamadaDeFerramenta("tu_9",
                        "ler_financeiro", json.createObjectNode()))),
                new Mensagem(Papel.USUARIO, List.of(new ResultadoDeFerramenta("tu_9",
                        "ferramenta não permitida para este usuário", true))));

        api().responder(new Pedido("s", mensagens, List.of(), 64));

        JsonNode enviadas = corpo.get().path("messages");
        assertThat(enviadas.get(1).path("content").get(0).path("type").asString())
                .isEqualTo("tool_use");
        JsonNode resultado = enviadas.get(2).path("content").get(0);
        assertThat(resultado.path("type").asString()).isEqualTo("tool_result");
        assertThat(resultado.path("tool_use_id").asString()).isEqualTo("tu_9");
        assertThat(resultado.path("is_error").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("sem ferramentas oferecidas, o campo tools nem vai")
    void sem_ferramentas() {
        resposta.set("""
                {"content":[{"type":"text","text":"ok"}],"usage":{"input_tokens":1,"output_tokens":1}}""");

        api().responder(pedidoSimples(List.of()));

        assertThat(corpo.get().has("tools")).isFalse();
    }

    @Test
    @DisplayName("429, 529 e 401 voltam para a fila; 400 não")
    void classificacao_de_falhas() {
        for (int transitorio : new int[] { 429, 529, 401, 503 }) {
            status.set(transitorio);
            assertThatThrownBy(() -> api().responder(pedidoSimples(List.of())))
                    .as("status " + transitorio).isInstanceOf(FalhaTransitoria.class);
        }
        status.set(400);
        assertThatThrownBy(() -> api().responder(pedidoSimples(List.of())))
                .isInstanceOf(FalhaPermanente.class);
    }

    @Test
    @DisplayName("o corpo do erro não vai para a mensagem: ele ecoa o pedido, que tem a mensagem da cliente")
    void corpo_de_erro_nao_vaza() {
        status.set(400);
        resposta.set("{\"error\":{\"message\":\"texto da cliente: Maria, 11988887777\"}}");

        assertThatThrownBy(() -> api().responder(pedidoSimples(List.of())))
                .isInstanceOf(FalhaPermanente.class)
                .hasMessageNotContaining("11988887777")
                .hasMessageNotContaining("Maria");
    }

    @Test
    @DisplayName("redirecionamento não é seguido: a chave não vai para outro endereço")
    void redirecionamento() {
        status.set(302);

        assertThatThrownBy(() -> api().responder(pedidoSimples(List.of())))
                .isInstanceOf(FalhaPermanente.class);
        assertThat(pedidosAoDestino).hasValue(0);
    }

    @Test
    @DisplayName("sem chave não está configurado, e o toString nunca mostra a chave")
    void configuracao() {
        assertThat(new ClaudeMessagesApi(" ", "claude-opus-5", json).configurado()).isFalse();
        assertThat(api().configurado()).isTrue();
        assertThat(api().toString()).doesNotContain("chave-teste");
    }
}
