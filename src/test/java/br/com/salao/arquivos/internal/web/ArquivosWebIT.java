package br.com.salao.arquivos.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * RT-ARQ-001 — o contrato HTTP do upload.
 *
 * <p>O que este teste protege, e o de aplicação não pode: que os <strong>três</strong> pedidos se
 * encaixem. O caso de uso prova que os bytes são validados; só daqui se prova que o endereço
 * devolvido no passo 1 é um endereço que aceita bytes, e que o passo 3 encontra o que foi escrito.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ArquivosWebIT extends AbstractPostgresIT {

    private static final String SENHA = "senha-bem-comprida-1";

    /** JPEG mínimo: os três bytes de assinatura bastam para a detecção. */
    private static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
            0x00, 0x10, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1 };

    @Value("${local.server.port}")
    private int porta;

    @Autowired
    private ProvisionarEstabelecimentoUseCase provisionar;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void criarSalaoComRecepcao() throws SQLException {
        provisionar.executar(new ProvisionarEstabelecimentoCommand(
                "Salão", null, null, null, null, null, null, "11",
                "Ana", "ana@salao.test", SENHA));
        // Recepção: quem anexa foto na ficha da cliente é quem atende, e a permissão do upload é
        // a de escrever no dono — não uma permissão de "upload" separada.
        try (var ps = comoOwner().prepareStatement("update usuario set perfil = ?")) {
            ps.setString(1, Perfil.RECEPCAO.name());
            ps.executeUpdate();
        }
    }

    @Test
    @DisplayName("os três pedidos se encaixam: autoriza, escreve, confirma")
    void caminho_completo() throws Exception {
        String token = entrar();

        var autorizacao = post("/api/v1/arquivos",
                "{\"donoTipo\":\"CLIENTE\",\"donoId\":\"" + UUID.randomUUID()
                        + "\",\"nomeOriginal\":\"foto.jpg\"}", token);
        assertThat(autorizacao.statusCode()).isEqualTo(201);

        String destino = campo(autorizacao.body(), "urlDeUpload");
        assertThat(putBytes(destino, JPEG, token).statusCode())
                .as("o endereço devolvido no passo 1 aceita bytes").isEqualTo(204);

        var confirmacao = post("/api/v1/arquivos/" + campo(autorizacao.body(), "arquivoId")
                + "/confirmacao", "", token);
        assertThat(confirmacao.statusCode()).isEqualTo(200);
        assertThat(confirmacao.body()).contains("VALIDADO").contains("JPEG");
    }

    @Test
    @DisplayName("recusa responde 200 com o motivo, não erro HTTP")
    void recusa_responde_200() throws Exception {
        // RN-ARQ-003: o pedido estava certo e o conteúdo é que não serve. Virar 4xx exigiria
        // lançar — e lançar de dentro da transação desfaria o registro da própria recusa.
        String token = entrar();
        var autorizacao = post("/api/v1/arquivos",
                "{\"donoTipo\":\"CLIENTE\",\"donoId\":\"" + UUID.randomUUID()
                        + "\",\"nomeOriginal\":\"foto.jpg\"}", token);
        putBytes(campo(autorizacao.body(), "urlDeUpload"),
                "<html><script>alert(1)</script></html>".getBytes(), token);

        var confirmacao = post("/api/v1/arquivos/" + campo(autorizacao.body(), "arquivoId")
                + "/confirmacao", "", token);

        assertThat(confirmacao.statusCode()).isEqualTo(200);
        assertThat(confirmacao.body()).contains("RECUSADO").contains("não é uma imagem");
    }

    @Test
    @DisplayName("a resposta não devolve a chave do armazenamento")
    void resposta_nao_vaza_a_chave() throws Exception {
        String token = entrar();
        var autorizacao = post("/api/v1/arquivos",
                "{\"donoTipo\":\"CLIENTE\",\"donoId\":\"" + UUID.randomUUID() + "\"}", token);
        putBytes(campo(autorizacao.body(), "urlDeUpload"), JPEG, token);

        var confirmacao = post("/api/v1/arquivos/" + campo(autorizacao.body(), "arquivoId")
                + "/confirmacao", "", token);

        assertThat(confirmacao.body())
                .as("chave é endereço de armazenamento; o que a tela recebe é URL com validade")
                .doesNotContain("\"chave\"");
    }

    @Test
    @DisplayName("escrever numa chave de outro salão é recusado")
    void chave_de_outro_salao() throws Exception {
        // A chave começa pelo tenant (RN-ARQ-002). Autenticado o usuário está — o que ele não tem
        // é aquele salão, e é só essa linha que separa uma coisa da outra.
        String token = entrar();
        var autorizacao = post("/api/v1/arquivos",
                "{\"donoTipo\":\"CLIENTE\",\"donoId\":\"" + UUID.randomUUID() + "\"}", token);

        String url = campo(autorizacao.body(), "urlDeUpload");
        String prefixo = "/api/v1/arquivos/conteudo/";
        String chaveAlheia = prefixo + UUID.randomUUID()
                + url.substring(url.indexOf('/', prefixo.length()));

        var recusa = putBytes(chaveAlheia, JPEG, token);

        assertThat(recusa.statusCode()).isEqualTo(403);
        assertThat(recusa.body()).contains("ER-ARQ-CHAVE_DE_OUTRO_SALAO");
    }

    @Test
    @DisplayName("dono de tipo desconhecido para na borda, não no check do banco")
    void dono_invalido_para_na_borda() throws Exception {
        // Sem o Pattern, o valor chegaria ao insert e o check do banco responderia — com uma
        // mensagem de constraint que não diz nada a quem chamou.
        String token = entrar();

        assertThat(post("/api/v1/arquivos",
                "{\"donoTipo\":\"FORNECEDOR\",\"donoId\":\"" + UUID.randomUUID() + "\"}", token)
                .statusCode()).isIn(400, 422);
    }

    @Test
    @DisplayName("sem token não se autoriza upload nem se escreve conteúdo")
    void sem_token_nao_entra() throws Exception {
        assertThat(post("/api/v1/arquivos",
                "{\"donoTipo\":\"CLIENTE\",\"donoId\":\"" + UUID.randomUUID() + "\"}", null)
                .statusCode()).isIn(401, 403);
        assertThat(putBytes("/api/v1/arquivos/conteudo/" + UUID.randomUUID() + "/cliente/x",
                JPEG, null).statusCode()).isIn(401, 403);
    }

    private String campo(String json, String nome) {
        var m = Pattern.compile("\"" + nome + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        assertThat(m.find()).as("campo %s presente em %s", nome, json).isTrue();
        return m.group(1);
    }

    private String entrar() throws Exception {
        var resposta = post("/api/v1/auth/login",
                "{\"email\":\"ana@salao.test\",\"senha\":\"" + SENHA + "\"}", null);
        var m = Pattern.compile("\"tokenDeAcesso\"\\s*:\\s*\"([^\"]+)\"").matcher(resposta.body());
        return m.find() ? m.group(1) : null;
    }

    private HttpResponse<String> post(String caminho, String corpo, String token) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(corpo));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> putBytes(String caminho, byte[] corpo, String token)
            throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho))
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(corpo));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
