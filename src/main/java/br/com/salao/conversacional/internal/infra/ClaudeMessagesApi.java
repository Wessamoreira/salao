package br.com.salao.conversacional.internal.infra;

import br.com.salao.conversacional.internal.application.FalhaPermanente;
import br.com.salao.conversacional.internal.application.FalhaTransitoria;
import br.com.salao.conversacional.internal.application.ModeloDeLinguagem;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * RT-CNV-004 — o modelo pela Messages API da Anthropic.
 *
 * <p>Atrás do port {@link ModeloDeLinguagem}: trocar de provedor é trocar este bean. Sem chave,
 * {@link #configurado()} é falso e o agente não roda.
 *
 * <h2>A chave vai para um endereço só</h2>
 *
 * <p>O endpoint é fixo no bean de produção — não é propriedade. Uma chave "URL do provedor"
 * configurável seria a forma de mandar a chave de API para qualquer lugar numa linha de ambiente. O
 * construtor com endpoint existe para o teste, e é de pacote. Redirecionamento não é seguido, pelo
 * mesmo motivo do download da Meta (RN-CNV-007).
 *
 * <h2>O que é transitório</h2>
 *
 * <p>429, 529 (sobrecarga), 5xx, 401 e 403 voltam para a fila — os dois últimos porque chave vencida
 * se conserta configurando. 400 é pedido malformado: tentar de novo repete o erro e o custo.
 */
public class ClaudeMessagesApi implements ModeloDeLinguagem {

    static final URI ENDPOINT = URI.create("https://api.anthropic.com/v1/messages");
    private static final String VERSAO_DA_API = "2023-06-01";
    private static final long TETO_DA_RESPOSTA = 1024 * 1024;
    private static final Set<Integer> TRANSITORIOS = Set.of(401, 403, 408, 429, 500, 502, 503, 504, 529);

    private final HttpClient http;
    private final URI endpoint;
    private final String chave;
    private final String modelo;
    private final ObjectMapper json;

    public ClaudeMessagesApi(String chave, String modelo, ObjectMapper json) {
        this(ENDPOINT, chave, modelo, json);
    }

    ClaudeMessagesApi(URI endpoint, String chave, String modelo, ObjectMapper json) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.endpoint = endpoint;
        this.chave = chave == null ? "" : chave.strip();
        this.modelo = modelo == null ? "" : modelo.strip();
        this.json = json;
    }

    @Override
    public boolean configurado() {
        return !chave.isEmpty() && !modelo.isEmpty();
    }

    @Override
    public String nome() {
        return modelo;
    }

    @Override
    public Resposta responder(Pedido pedido) {
        byte[] corpo = json.writeValueAsBytes(montar(pedido));
        HttpRequest requisicao = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(90))
                .header("x-api-key", chave)
                .header("anthropic-version", VERSAO_DA_API)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(corpo))
                .build();
        try {
            HttpResponse<InputStream> resposta =
                    http.send(requisicao, HttpResponse.BodyHandlers.ofInputStream());
            byte[] lido;
            try (InputStream entrada = resposta.body()) {
                lido = entrada.readNBytes((int) TETO_DA_RESPOSTA + 1);
            }
            if (lido.length > TETO_DA_RESPOSTA) {
                throw new FalhaTransitoria("resposta do modelo acima do teto");
            }
            int status = resposta.statusCode();
            if (status != 200) {
                // Só o status: o corpo de erro às vezes ecoa o pedido, e o pedido tem a mensagem
                // da cliente. Isto vai para `ultimo_erro`.
                String mensagem = "modelo respondeu " + status;
                throw TRANSITORIOS.contains(status)
                        ? new FalhaTransitoria(mensagem) : new FalhaPermanente(mensagem);
            }
            return ler(lido);
        } catch (IOException e) {
            throw new FalhaTransitoria("modelo: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FalhaTransitoria("modelo interrompido", e);
        }
    }

    private ObjectNode montar(Pedido pedido) {
        ObjectNode corpo = json.createObjectNode();
        corpo.put("model", modelo);
        corpo.put("max_tokens", pedido.maxTokens());
        corpo.put("system", pedido.sistema());
        if (!pedido.ferramentas().isEmpty()) {
            ArrayNode ferramentas = corpo.putArray("tools");
            for (FerramentaOferecida oferta : pedido.ferramentas()) {
                ferramentas.addObject()
                        .put("name", oferta.nome())
                        .put("description", oferta.descricao())
                        .set("input_schema", oferta.esquema());
            }
        }
        ArrayNode mensagens = corpo.putArray("messages");
        for (Mensagem mensagem : pedido.mensagens()) {
            ObjectNode item = mensagens.addObject();
            item.put("role", mensagem.papel() == Papel.USUARIO ? "user" : "assistant");
            ArrayNode conteudo = item.putArray("content");
            for (Bloco bloco : mensagem.blocos()) {
                switch (bloco) {
                    case Texto t -> conteudo.addObject().put("type", "text").put("text", t.texto());
                    case ChamadaDeFerramenta c -> conteudo.addObject()
                            .put("type", "tool_use").put("id", c.id()).put("name", c.nome())
                            .set("input", c.argumentos() == null
                                    ? json.createObjectNode() : c.argumentos());
                    case ResultadoDeFerramenta r -> conteudo.addObject()
                            .put("type", "tool_result").put("tool_use_id", r.idDaChamada())
                            .put("content", r.conteudo()).put("is_error", r.erro());
                }
            }
        }
        return corpo;
    }

    private Resposta ler(byte[] corpo) {
        JsonNode raiz;
        try {
            raiz = json.readTree(corpo);
        } catch (JacksonException e) {
            throw new FalhaTransitoria("resposta do modelo ilegível");
        }
        List<Bloco> blocos = new ArrayList<>();
        for (JsonNode bloco : raiz.path("content")) {
            switch (bloco.path("type").asString()) {
                case "text" -> blocos.add(new Texto(bloco.path("text").asString()));
                case "tool_use" -> blocos.add(new ChamadaDeFerramenta(
                        bloco.path("id").asString(), bloco.path("name").asString(),
                        bloco.path("input")));
                default -> {
                    // Tipos que não usamos (raciocínio, por exemplo) não viram nada.
                }
            }
        }
        JsonNode uso = raiz.path("usage");
        return new Resposta(blocos, uso.path("input_tokens").asInt(0),
                uso.path("output_tokens").asInt(0));
    }

    /** Para o log de subida: nunca a chave. */
    @Override
    public String toString() {
        return "ClaudeMessagesApi[" + modelo + ", chave " + (chave.isEmpty() ? "ausente" : "presente")
                + "]";
    }
}
