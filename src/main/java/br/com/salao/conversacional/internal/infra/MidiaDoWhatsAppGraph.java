package br.com.salao.conversacional.internal.infra;

import br.com.salao.conversacional.internal.application.FalhaPermanente;
import br.com.salao.conversacional.internal.application.FalhaTransitoria;
import br.com.salao.conversacional.internal.application.MidiaDoWhatsApp;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * RT-CNV-003 — a mídia pela Graph API da Meta. A primeira chamada do sistema para fora.
 *
 * <p>Dois pedidos: o metadado ({@code GET /{media-id}}), que devolve uma URL de curta validade, e o
 * conteúdo dessa URL — os dois com o token do app no cabeçalho, porque a Meta exige.
 *
 * <h2>O token vai no segundo pedido, e é aí que mora o risco — RN-CNV-007</h2>
 *
 * <p>A URL do conteúdo vem na RESPOSTA. Se ela apontasse para outro host, o {@code Authorization}
 * iria junto e o token do app — que lê e manda mensagem por todos os salões — vazaria para quem
 * controlasse aquele host. Por isso, antes do segundo pedido:
 *
 * <ul>
 *   <li>o host precisa estar na lista da Meta, casando por SUFIXO COM PONTO ({@code .fbsbx.com}) —
 *       {@code fbsbx.com.atacante.net} e {@code atacantefbsbx.com} não passam;
 *   <li>o esquema precisa ser https;
 *   <li>redirecionamento NUNCA é seguido: um 302 levaria o cabeçalho para onde a lista não olhou.
 * </ul>
 *
 * <p>A política entra pelo construtor e não por propriedade. Uma chave de configuração "permitir
 * http" ou "hosts extras" seria uma chave que alguém vira em produção para destravar um teste.
 *
 * <h2>O id da mídia vai no CAMINHO da URL</h2>
 *
 * <p>Vem de mensagem assinada pela Meta, e mesmo assim é conferido contra {@code [A-Za-z0-9_-]}:
 * texto de fora não compõe caminho sem conferência — {@code ../} num id montaria outra rota da Graph
 * API com o token do app.
 *
 * <h2>Tamanho e integridade</h2>
 *
 * <p>Teto de 16 MB — o limite de áudio do WhatsApp —, conferido no metadado E na leitura, porque o
 * metadado é uma afirmação e a leitura é o que chega. E o sha256 do metadado é conferido contra os
 * bytes: um áudio adulterado no caminho não vira transcrição.
 */
public class MidiaDoWhatsAppGraph implements MidiaDoWhatsApp {

    /** O limite de áudio do WhatsApp. */
    static final long TETO_PADRAO = 16L * 1024 * 1024;

    /** O metadado é um JSON de poucas linhas. */
    private static final long TETO_DO_METADADO = 64 * 1024;

    private static final Pattern ID_DE_MIDIA = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private final HttpClient http;
    private final String graphUrl;
    private final String token;
    private final PoliticaDeUrl politica;
    private final ObjectMapper json;
    private final long teto;

    public MidiaDoWhatsAppGraph(String graphUrl, String token, PoliticaDeUrl politica,
                                ObjectMapper json) {
        this(graphUrl, token, politica, json, TETO_PADRAO);
    }

    /** Para teste: um teto pequeno prova o corte na leitura sem trafegar 16 MB. */
    MidiaDoWhatsAppGraph(String graphUrl, String token, PoliticaDeUrl politica, ObjectMapper json,
                         long teto) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.graphUrl = graphUrl == null ? "" : graphUrl.replaceAll("/+$", "");
        this.token = token == null ? "" : token.strip();
        this.politica = politica;
        this.json = json;
        this.teto = teto;
    }

    /**
     * Para onde o token pode ir.
     *
     * @param sufixosDeHost hosts aceitos, casados exatamente ou como sufixo precedido de ponto
     */
    public record PoliticaDeUrl(Set<String> sufixosDeHost, boolean exigirHttps) {

        /** Os CDNs em que a Meta serve mídia do WhatsApp. */
        public static PoliticaDeUrl daMeta() {
            return new PoliticaDeUrl(Set.of("fbsbx.com", "fbcdn.net", "whatsapp.net"), true);
        }

        public boolean permite(URI uri) {
            if (uri == null || uri.getHost() == null) {
                return false;
            }
            if (exigirHttps && !"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return sufixosDeHost.stream().anyMatch(s -> host.equals(s) || host.endsWith("." + s));
        }
    }

    @Override
    public boolean configurado() {
        return !token.isEmpty() && !graphUrl.isEmpty();
    }

    @Override
    public MidiaBaixada baixar(String midiaId) {
        if (midiaId == null || !ID_DE_MIDIA.matcher(midiaId).matches()) {
            throw new FalhaPermanente("id de mídia com formato inesperado");
        }

        JsonNode metadado = lerJson(pedir(URI.create(graphUrl + "/" + midiaId),
                Duration.ofSeconds(15), TETO_DO_METADADO, "metadado"));

        String url = texto(metadado.path("url"));
        if (url == null) {
            throw new FalhaTransitoria("metadado sem URL de conteúdo");
        }
        URI destino;
        try {
            destino = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new FalhaPermanente("URL de conteúdo inválida");
        }
        if (!politica.permite(destino)) {
            // O pedido não sai: o token não vai para host que não seja da Meta.
            throw new FalhaPermanente("URL de conteúdo fora dos hosts da Meta");
        }

        JsonNode tamanho = metadado.path("file_size");
        if (tamanho.isNumber() && tamanho.longValue() > teto) {
            throw new FalhaPermanente("mídia com " + tamanho.longValue() + " bytes, acima do teto");
        }

        byte[] conteudo = pedir(destino, Duration.ofSeconds(60), teto, "conteúdo");
        conferirIntegridade(conteudo, texto(metadado.path("sha256")));
        return new MidiaBaixada(conteudo, texto(metadado.path("mime_type")));
    }

    private byte[] pedir(URI uri, Duration prazo, long limite, String etapa) {
        HttpRequest pedido = HttpRequest.newBuilder(uri)
                .timeout(prazo)
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        try {
            HttpResponse<InputStream> resposta =
                    http.send(pedido, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream corpo = resposta.body()) {
                int status = resposta.statusCode();
                if (status != 200) {
                    throw falhaPorStatus(status, etapa);
                }
                byte[] lido = corpo.readNBytes((int) Math.min(limite + 1, Integer.MAX_VALUE));
                if (lido.length > limite) {
                    throw new FalhaPermanente(etapa + " acima do teto de " + limite + " bytes");
                }
                return lido;
            }
        } catch (IOException e) {
            throw new FalhaTransitoria(etapa + ": " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FalhaTransitoria(etapa + " interrompido", e);
        }
    }

    /**
     * 5xx, 429, 401 e 403 voltam para a fila; o resto, não.
     *
     * <p>401/403 é token vencido ou trocado: conserta-se configurando, e a linha não deve morrer na
     * primeira tentativa por causa disso — morre na quinta, se ninguém consertar. 3xx cai em
     * permanente, porque redirecionamento não é seguido (ver o javadoc da classe).
     */
    private static RuntimeException falhaPorStatus(int status, String etapa) {
        if (status == 429 || status == 401 || status == 403 || status >= 500) {
            return new FalhaTransitoria(etapa + " respondeu " + status);
        }
        return new FalhaPermanente(etapa + " respondeu " + status);
    }

    private JsonNode lerJson(byte[] corpo) {
        try {
            return json.readTree(corpo);
        } catch (JacksonException e) {
            // Um 200 com corpo que não é JSON costuma ser página de erro de proxy no meio do caminho.
            throw new FalhaTransitoria("metadado ilegível");
        }
    }

    /**
     * Confere o sha256 declarado contra os bytes.
     *
     * <p>Aceita hexadecimal ou Base64: a documentação da Meta mostra o campo sem dizer a codificação.
     * Sem sha256 no metadado, não há o que conferir — e isso não é motivo para recusar o áudio.
     */
    private static void conferirIntegridade(byte[] conteudo, String declarado) {
        if (declarado == null) {
            return;
        }
        byte[] resumo;
        try {
            resumo = MessageDigest.getInstance("SHA-256").digest(conteudo);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
        String hex = HexFormat.of().formatHex(resumo);
        String base64 = Base64.getEncoder().encodeToString(resumo);
        if (!declarado.equalsIgnoreCase(hex) && !declarado.equals(base64)) {
            throw new FalhaPermanente("o conteúdo não confere com o sha256 declarado");
        }
    }

    private static String texto(JsonNode no) {
        if (no == null || !no.isValueNode()) {
            return null;
        }
        String valor = no.asString();
        return valor == null || valor.isBlank() ? null : valor;
    }
}
