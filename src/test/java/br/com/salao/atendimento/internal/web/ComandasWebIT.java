package br.com.salao.atendimento.internal.web;

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
 * RT-ATD-001 — o contrato HTTP da comanda.
 *
 * <p>O que este teste protege, e o de aplicação não pode: que <strong>reabrir responda 200</strong>.
 * 201 significa "criei", e anunciar criação de uma comanda que está aberta desde as nove da manhã —
 * com itens dentro — é mentir para quem está no balcão.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ComandasWebIT extends AbstractPostgresIT {

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
        trocarPerfil(Perfil.RECEPCAO);
    }

    private void trocarPerfil(Perfil perfil) throws SQLException {
        try (var ps = comoOwner().prepareStatement("update usuario set perfil = ?")) {
            ps.setString(1, perfil.name());
            ps.executeUpdate();
        }
    }

    private String umaCliente(String token) throws Exception {
        var criada = post("/api/v1/clientes", "{\"nome\":\"Maria Silva\"}", token);
        String local = criada.headers().firstValue("Location").orElseThrow();
        return local.substring(local.lastIndexOf('/') + 1);
    }

    @Test
    @DisplayName("abre avulsa com 201 e Location")
    void abre_com_201() throws Exception {
        String token = entrar();
        String maria = umaCliente(token);

        var resposta = post("/api/v1/comandas", "{\"clienteId\":\"" + maria + "\"}", token);

        assertThat(resposta.statusCode()).isEqualTo(201);
        assertThat(resposta.headers().firstValue("Location")).isPresent();
        assertThat(resposta.body()).contains("\"jaEstavaAberta\":false").contains("\"numero\":1");
    }

    @Test
    @DisplayName("abrir de novo responde 200, não 201")
    void reabrir_responde_200() throws Exception {
        String token = entrar();
        String maria = umaCliente(token);
        post("/api/v1/comandas", "{\"clienteId\":\"" + maria + "\"}", token);

        var segunda = post("/api/v1/comandas", "{\"clienteId\":\"" + maria + "\"}", token);

        assertThat(segunda.statusCode())
                .as("nada foi criado; 201 diria o contrário").isEqualTo(200);
        assertThat(segunda.body()).contains("\"jaEstavaAberta\":true");
    }

    @Test
    @DisplayName("os dois campos juntos são recusados na borda")
    void agendamento_e_cliente_juntos() throws Exception {
        // Qual dos dois valeria? Aceitar os dois é deixar a resposta para o acaso da ordem do if.
        String token = entrar();
        String maria = umaCliente(token);

        var resposta = post("/api/v1/comandas", "{\"clienteId\":\"" + maria
                + "\",\"agendamentoId\":\"" + java.util.UUID.randomUUID() + "\"}", token);

        assertThat(resposta.statusCode()).isIn(400, 422);
    }

    @Test
    @DisplayName("nenhum dos dois também é recusado")
    void sem_nenhum_dos_dois() throws Exception {
        assertThat(post("/api/v1/comandas", "{}", entrar()).statusCode()).isIn(400, 422);
    }

    @Test
    @DisplayName("a barra do balcão traz a comanda com o nome de quem está na cadeira")
    void barra_traz_o_nome() throws Exception {
        String token = entrar();
        String maria = umaCliente(token);
        post("/api/v1/comandas", "{\"clienteId\":\"" + maria + "\"}", token);

        var barra = get("/api/v1/comandas/abertas", token);

        assertThat(barra.statusCode()).isEqualTo(200);
        assertThat(barra.body()).contains("Maria Silva").contains("ABERTA");
    }

    @Test
    @DisplayName("o profissional não abre comanda")
    void profissional_nao_abre() throws Exception {
        // O perfil PROFISSIONAL não tem comanda:open — ele atende, não opera o caixa. E a recusa
        // é 403, não 500: negar permissão é desfecho normal.
        String token = entrar();
        String maria = umaCliente(token);
        trocarPerfil(Perfil.PROFISSIONAL);
        String tokenDoProfissional = entrar();

        var recusa = post("/api/v1/comandas", "{\"clienteId\":\"" + maria + "\"}",
                tokenDoProfissional);

        assertThat(recusa.statusCode()).isEqualTo(403);
        assertThat(recusa.body()).contains("ER-INF-ACESSO_NEGADO");
    }

    @Test
    @DisplayName("sem token, o balcão não abre")
    void sem_token() throws Exception {
        assertThat(post("/api/v1/comandas", "{\"clienteId\":\""
                + java.util.UUID.randomUUID() + "\"}", null).statusCode()).isIn(401, 403);
        assertThat(get("/api/v1/comandas/abertas", null).statusCode()).isIn(401, 403);
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
