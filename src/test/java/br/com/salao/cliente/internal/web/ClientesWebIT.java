package br.com.salao.cliente.internal.web;

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
 * RT-CLI-001 — o contrato HTTP do cadastro.
 *
 * <p>O que este teste protege, e o de aplicação não pode: a validação de <strong>borda</strong>.
 * O campo {@code telefone} não tem {@code @Pattern} de propósito, e um zeloso futuro que
 * adicionasse um recusaria {@code "(11) 98765-4321"} com um 400 genérico, antes de o domínio ter
 * chance de normalizar. Aqui isso reprova o build.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClientesWebIT extends AbstractPostgresIT {

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
        // Recepção em vez de admin: quem cadastra cliente no balcão é a recepção, e ela não
        // exige MFA — o segundo fator já tem teste próprio em AutorizacaoWebIT.
        try (var ps = comoOwner().prepareStatement("update usuario set perfil = ?")) {
            ps.setString(1, Perfil.RECEPCAO.name());
            ps.executeUpdate();
        }
    }

    @Test
    @DisplayName("telefone com máscara passa pela borda e chega normalizado")
    void telefone_com_mascara_e_aceito() throws Exception {
        String token = entrar();

        var criacao = post("/api/v1/clientes",
                "{\"nome\":\"Maria Silva\",\"telefone\":\"(11) 98765-4321\"}", token);

        assertThat(criacao.statusCode()).isEqualTo(201);
        String local = criacao.headers().firstValue("Location").orElseThrow();

        var ficha = get(local, token);
        assertThat(ficha.statusCode()).isEqualTo(200);
        assertThat(ficha.body()).contains("\"telefone\":\"5511987654321\"");
    }

    @Test
    @DisplayName("telefone impossível volta 422 com o campo apontado, não 400 genérico")
    void telefone_invalido_aponta_o_campo() throws Exception {
        // O front precisa saber ONDE está o erro para destacar o campo. Um 400 de validação de
        // borda diria só "requisição inválida", e a recepção ficaria procurando.
        String token = entrar();

        var resposta = post("/api/v1/clientes",
                "{\"nome\":\"Maria\",\"telefone\":\"1234\"}", token);

        assertThat(resposta.statusCode()).isEqualTo(422);
        assertThat(resposta.body()).contains("ER-CLI-TELEFONE_INVALIDO").contains("telefone");
    }

    @Test
    @DisplayName("nome ausente é recusado ainda na borda")
    void nome_ausente_e_recusado() throws Exception {
        String token = entrar();

        assertThat(post("/api/v1/clientes", "{\"telefone\":\"11987654321\"}", token).statusCode())
                .isIn(400, 422);
    }

    @Test
    @DisplayName("sem token, o cadastro não abre")
    void sem_token_nao_cadastra() throws Exception {
        assertThat(post("/api/v1/clientes", "{\"nome\":\"Maria\"}", null).statusCode())
                .isIn(401, 403);
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

    private HttpResponse<String> get(String caminho, String token) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho)).GET();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
