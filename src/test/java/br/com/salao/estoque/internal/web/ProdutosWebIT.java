package br.com.salao.estoque.internal.web;

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
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * RT-EST-001 — o contrato HTTP do cadastro de produto.
 *
 * <p>O que este teste protege, e o de aplicação não pode: que a <strong>separação de permissões
 * sobreviva à porta</strong>. A recepção cadastra produto e não precifica — se o preço estivesse no
 * mesmo corpo do cadastro, a rota inteira exigiria a permissão de preço, e a recepção deixaria de
 * conseguir cadastrar.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProdutosWebIT extends AbstractPostgresIT {

    private static final String SENHA = "senha-bem-comprida-1";

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
        try (var ps = comoOwner().prepareStatement("update usuario set perfil = ?")) {
            ps.setString(1, Perfil.RECEPCAO.name());
            ps.executeUpdate();
        }
    }

    private String idDe(HttpResponse<String> resposta) {
        String local = resposta.headers().firstValue("Location").orElseThrow();
        return local.substring(local.lastIndexOf('/') + 1);
    }

    @Test
    @DisplayName("a recepção cadastra produto")
    void recepcao_cadastra() throws Exception {
        String token = entrar();

        var resposta = post("/api/v1/produtos", """
                {"nome":"Shampoo Hidratante","finalidade":"VENDA","unidade":"UN"}
                """, token);

        assertThat(resposta.statusCode()).isEqualTo(201);
        assertThat(resposta.headers().firstValue("Location")).isPresent();
        assertThat(resposta.body()).contains("Shampoo Hidratante").contains("\"vendavel\":false");
    }

    @Test
    @DisplayName("a recepção NÃO precifica, nem no cadastro nem depois")
    void recepcao_nao_precifica() throws Exception {
        String token = entrar();

        var comPreco = post("/api/v1/produtos", """
                {"nome":"Condicionador","finalidade":"VENDA","unidade":"UN","precoVenda":40}
                """, token);
        assertThat(comPreco.statusCode())
                .as("403, e não 500: negar permissão é desfecho normal").isEqualTo(403);

        String id = idDe(post("/api/v1/produtos", """
                {"nome":"Condicionador","finalidade":"VENDA","unidade":"UN"}
                """, token));
        assertThat(put("/api/v1/produtos/" + id + "/preco", "{\"valor\":40}", token).statusCode())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("unidade fora da lista para na borda, não no check do banco")
    void unidade_invalida() throws Exception {
        // Sem a lista fechada, "un", "und" e "unid" viram três unidades, e a soma de saldo passa a
        // depender de como cada pessoa escreveu.
        String token = entrar();

        assertThat(post("/api/v1/produtos", """
                {"nome":"Creme","finalidade":"VENDA","unidade":"POTE"}
                """, token).statusCode()).isIn(400, 422);
    }

    @Test
    @DisplayName("salvar por cima de uma tela velha responde 409")
    void conflito_de_versao() throws Exception {
        String token = entrar();
        String id = idDe(post("/api/v1/produtos", """
                {"nome":"Tinta 7.1","finalidade":"CONSUMO_INTERNO","unidade":"G"}
                """, token));
        String corpo = """
                {"nome":"Tinta 7.1 Louro","finalidade":"CONSUMO_INTERNO","unidade":"G"}
                """;
        assertThat(put("/api/v1/produtos/" + id + "?versao=0", corpo, token).statusCode())
                .isEqualTo(200);

        var segunda = put("/api/v1/produtos/" + id + "?versao=0", corpo, token);

        assertThat(segunda.statusCode()).isEqualTo(409);
        assertThat(segunda.body()).contains("ER-EST-CONFLITO_DE_VERSAO");
    }

    @Test
    @DisplayName("a lista do balcão não traz produto de consumo interno")
    void lista_do_balcao() throws Exception {
        String token = entrar();
        post("/api/v1/produtos", """
                {"nome":"Shampoo","finalidade":"VENDA","unidade":"UN"}
                """, token);
        post("/api/v1/produtos", """
                {"nome":"Tinta 7.1","finalidade":"CONSUMO_INTERNO","unidade":"G"}
                """, token);

        var balcao = get("/api/v1/produtos?somenteVendaveis=true", token);

        assertThat(balcao.statusCode()).isEqualTo(200);
        assertThat(balcao.body()).contains("Shampoo").doesNotContain("Tinta");
    }

    @Test
    @DisplayName("sem token o cadastro não abre")
    void sem_token() throws Exception {
        assertThat(post("/api/v1/produtos", """
                {"nome":"Shampoo","finalidade":"VENDA","unidade":"UN"}
                """, null).statusCode()).isIn(401, 403);
        assertThat(get("/api/v1/produtos", null).statusCode()).isIn(401, 403);
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

    private HttpResponse<String> put(String caminho, String corpo, String token) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(corpo));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String caminho, String token) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho)).GET();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
