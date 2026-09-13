package br.com.salao.conversacional.internal.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.salao.conversacional.internal.application.FalhaPermanente;
import br.com.salao.conversacional.internal.application.FalhaTransitoria;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * RT-CNV-003 / RN-CNV-007 — a Graph API contra servidores de verdade, do próprio JDK.
 *
 * <p>Dois servidores: um faz o papel da Meta, e o outro é o "host estranho" para onde uma resposta
 * adulterada tentaria mandar o download. O teste que importa não é o que baixa: é o que prova que o
 * host estranho recebeu ZERO pedidos — o token do app nunca saiu.
 */
class MidiaDoWhatsAppGraphTest {

    private static final String TOKEN = "token-de-teste";
    private static final long TETO = 1024;

    private HttpServer meta;
    private HttpServer estranho;
    private final List<String> autorizacoes = new CopyOnWriteArrayList<>();
    private final AtomicInteger pedidosDeConteudo = new AtomicInteger();
    private final AtomicInteger pedidosAoEstranho = new AtomicInteger();
    private final byte[] audio = ogg();

    private static byte[] ogg() {
        byte[] b = new byte[256];
        System.arraycopy("OggS".getBytes(StandardCharsets.US_ASCII), 0, b, 0, 4);
        for (int i = 4; i < b.length; i++) {
            b[i] = (byte) i;
        }
        return b;
    }

    private static String sha256Hex(byte[] dados) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(dados));
    }

    @BeforeEach
    void subir() throws Exception {
        meta = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        estranho = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + meta.getAddress().getPort();
        String hexCerto = sha256Hex(audio);
        String hexOutro = sha256Hex("outra coisa".getBytes(StandardCharsets.UTF_8));
        String b64Certo = Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(audio));

        meta.createContext("/v23.0/", troca -> {
            autorizacoes.add(String.valueOf(troca.getRequestHeaders().getFirst("Authorization")));
            String id = troca.getRequestURI().getPath().substring("/v23.0/".length());
            switch (id) {
                case "MIDIA1" -> json(troca, metadado(base + "/conteudo/ok", hexCerto, audio.length));
                case "BASE64" -> json(troca, metadado(base + "/conteudo/ok", b64Certo, audio.length));
                case "EXPIRADA" -> responder(troca, 404, new byte[0]);
                case "INSTAVEL" -> responder(troca, 500, new byte[0]);
                case "FORA" -> json(troca, metadado("http://localhost:"
                        + estranho.getAddress().getPort() + "/roubo", hexCerto, audio.length));
                case "GRANDE" -> json(troca, metadado(base + "/conteudo/ok", hexCerto, 4096));
                case "ADULTERADA" -> json(troca, metadado(base + "/conteudo/ok", hexOutro,
                        audio.length));
                case "REDIRECIONA" -> json(troca, metadado(base + "/conteudo/redireciona",
                        hexCerto, audio.length));
                case "ENORME" -> json(troca, """
                        {"url":"%s","mime_type":"audio/ogg"}""".formatted(base + "/conteudo/enorme"));
                case "SEMJSON" -> responder(troca, 200,
                        "<html>proxy</html>".getBytes(StandardCharsets.UTF_8));
                default -> responder(troca, 400, new byte[0]);
            }
        });
        meta.createContext("/conteudo/ok", troca -> {
            autorizacoes.add(String.valueOf(troca.getRequestHeaders().getFirst("Authorization")));
            pedidosDeConteudo.incrementAndGet();
            responder(troca, 200, audio);
        });
        meta.createContext("/conteudo/redireciona", troca -> {
            troca.getResponseHeaders().add("Location", base + "/conteudo/ok");
            responder(troca, 302, new byte[0]);
        });
        meta.createContext("/conteudo/enorme", troca -> responder(troca, 200, new byte[2048]));
        estranho.createContext("/", troca -> {
            pedidosAoEstranho.incrementAndGet();
            responder(troca, 200, new byte[0]);
        });
        meta.start();
        estranho.start();
    }

    @AfterEach
    void descer() {
        meta.stop(0);
        estranho.stop(0);
    }

    private static String metadado(String url, String sha, long tamanho) {
        return """
                {"url":"%s","mime_type":"audio/ogg; codecs=opus","sha256":"%s","file_size":%d,"id":"x"}
                """.formatted(url, sha, tamanho);
    }

    private static void json(HttpExchange troca, String corpo) throws IOException {
        troca.getResponseHeaders().add("Content-Type", "application/json");
        responder(troca, 200, corpo.getBytes(StandardCharsets.UTF_8));
    }

    private static void responder(HttpExchange troca, int status, byte[] corpo) throws IOException {
        troca.sendResponseHeaders(status, corpo.length == 0 ? -1 : corpo.length);
        if (corpo.length > 0) {
            troca.getResponseBody().write(corpo);
        }
        troca.close();
    }

    private MidiaDoWhatsAppGraph adaptador() {
        return new MidiaDoWhatsAppGraph("http://127.0.0.1:" + meta.getAddress().getPort() + "/v23.0/",
                TOKEN, new MidiaDoWhatsAppGraph.PoliticaDeUrl(Set.of("127.0.0.1"), false),
                JsonMapper.builder().build(), TETO);
    }

    @Test
    @DisplayName("baixa o áudio com o token nos dois pedidos, como a Meta exige")
    void baixa() {
        var baixada = adaptador().baixar("MIDIA1");

        assertThat(baixada.conteudo()).isEqualTo(audio);
        assertThat(baixada.mimeType()).isEqualTo("audio/ogg; codecs=opus");
        assertThat(autorizacoes).containsExactly("Bearer " + TOKEN, "Bearer " + TOKEN);
    }

    @Test
    @DisplayName("URL de conteúdo fora dos hosts permitidos: o host estranho recebe ZERO pedidos")
    void token_nao_sai_do_perimetro() {
        /*
         * A URL do conteúdo vem na RESPOSTA, e o Authorization iria junto. Uma resposta adulterada
         * apontando para outro host mandaria o token do app — o que lê e escreve por todos os
         * salões — para quem controla aquele host.
         */
        assertThatThrownBy(() -> adaptador().baixar("FORA")).isInstanceOf(FalhaPermanente.class);
        assertThat(pedidosAoEstranho).as("o token não pode ter saído").hasValue(0);
    }

    @Test
    @DisplayName("a política de produção: sufixo com ponto, https obrigatório")
    void politica_de_producao() {
        var meta = MidiaDoWhatsAppGraph.PoliticaDeUrl.daMeta();

        assertThat(meta.permite(URI.create("https://lookaside.fbsbx.com/whatsapp_business/x")))
                .isTrue();
        assertThat(meta.permite(URI.create("https://mmg.whatsapp.net/v/t62/x"))).isTrue();
        assertThat(meta.permite(URI.create("http://lookaside.fbsbx.com/x")))
                .as("sem https").isFalse();
        assertThat(meta.permite(URI.create("https://fbsbx.com.atacante.net/x")))
                .as("sufixo no meio").isFalse();
        assertThat(meta.permite(URI.create("https://atacantefbsbx.com/x")))
                .as("sufixo sem ponto").isFalse();
        assertThat(meta.permite(URI.create("https://127.0.0.1/x"))).isFalse();
    }

    @Test
    @DisplayName("id de mídia com caminho é recusado antes de qualquer pedido")
    void id_com_caminho() {
        // `../` no id montaria outra rota da Graph API — com o token do app.
        assertThatThrownBy(() -> adaptador().baixar("../../me/messages"))
                .isInstanceOf(FalhaPermanente.class);
        assertThat(autorizacoes).isEmpty();
    }

    @Test
    @DisplayName("mídia que não existe mais é falha permanente")
    void midia_expirada() {
        assertThatThrownBy(() -> adaptador().baixar("EXPIRADA"))
                .isInstanceOf(FalhaPermanente.class);
    }

    @Test
    @DisplayName("servidor instável é falha transitória")
    void servidor_instavel() {
        assertThatThrownBy(() -> adaptador().baixar("INSTAVEL"))
                .isInstanceOf(FalhaTransitoria.class);
    }

    @Test
    @DisplayName("servidor fora do ar é falha transitória")
    void servidor_fora() {
        var semServidor = new MidiaDoWhatsAppGraph("http://127.0.0.1:1/v23.0", TOKEN,
                new MidiaDoWhatsAppGraph.PoliticaDeUrl(Set.of("127.0.0.1"), false),
                JsonMapper.builder().build(), TETO);

        assertThatThrownBy(() -> semServidor.baixar("MIDIA1"))
                .isInstanceOf(FalhaTransitoria.class);
    }

    @Test
    @DisplayName("tamanho declarado acima do teto: nem baixa")
    void tamanho_declarado() {
        assertThatThrownBy(() -> adaptador().baixar("GRANDE")).isInstanceOf(FalhaPermanente.class);
        assertThat(pedidosDeConteudo).hasValue(0);
    }

    @Test
    @DisplayName("o teto vale na LEITURA, não só no que o metadado afirma")
    void teto_na_leitura() {
        // O metadado não declarou tamanho; o corpo veio com o dobro do teto.
        assertThatThrownBy(() -> adaptador().baixar("ENORME")).isInstanceOf(FalhaPermanente.class);
    }

    @Test
    @DisplayName("conteúdo que não confere com o sha256 é recusado")
    void sha_adulterado() {
        assertThatThrownBy(() -> adaptador().baixar("ADULTERADA"))
                .isInstanceOf(FalhaPermanente.class);
    }

    @Test
    @DisplayName("sha256 em Base64 também confere")
    void sha_em_base64() {
        assertThat(adaptador().baixar("BASE64").conteudo()).isEqualTo(audio);
    }

    @Test
    @DisplayName("redirecionamento não é seguido: levaria o token para onde a lista não olhou")
    void redirecionamento() {
        assertThatThrownBy(() -> adaptador().baixar("REDIRECIONA"))
                .isInstanceOf(FalhaPermanente.class);
        assertThat(pedidosDeConteudo).as("o destino do redirecionamento não foi chamado").hasValue(0);
    }

    @Test
    @DisplayName("metadado que não é JSON é transitório: costuma ser página de proxy")
    void metadado_ilegivel() {
        assertThatThrownBy(() -> adaptador().baixar("SEMJSON"))
                .isInstanceOf(FalhaTransitoria.class);
    }

    @Test
    @DisplayName("sem token não está configurado")
    void sem_token() {
        var semToken = new MidiaDoWhatsAppGraph("https://graph.facebook.com/v23.0", "  ",
                MidiaDoWhatsAppGraph.PoliticaDeUrl.daMeta(), JsonMapper.builder().build());

        assertThat(semToken.configurado()).isFalse();
    }
}
