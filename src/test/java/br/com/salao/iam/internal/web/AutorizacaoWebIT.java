package br.com.salao.iam.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.iam.internal.domain.Totp;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * RT-IAM-006 — autorização e imposição de segundo fator pelo HTTP.
 *
 * <p>Usa {@code /api/v1/agendamentos}, que ainda não existe, como sonda: <strong>403 significa
 * barrado pela autorização; 404 significa que passou por ela</strong> e só não achou o
 * controller. É o discriminador exato de que se precisa aqui.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AutorizacaoWebIT extends AbstractPostgresIT {

    private static final String SENHA = "senha-bem-comprida-1";
    private static final String ROTA_PROTEGIDA = "/api/v1/agendamentos";

    /**
     * Limite de taxa alto: esta classe exercita o fluxo de autenticação e faz muito mais
     * chamadas seguidas do que uma pessoa faria. O limite tem teste próprio
     * ({@code LimiteDeTaxaWebIT}) — afrouxá-lo aqui mantém cada teste sobre um assunto só,
     * em vez de fazer este falhar por um motivo que não é o dele.
     */
    @DynamicPropertySource
    static void semLimiteDeTaxa(DynamicPropertyRegistry registro) {
        registro.add("app.rede.limite-autenticacao", () -> "1000");
    }

    @Value("${local.server.port}")
    private int porta;

    @Autowired
    private ProvisionarEstabelecimentoUseCase provisionar;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void criarSalao() {
        provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão", null, "Ana", "ana@salao.test", SENHA));
    }

    @Test
    @DisplayName("admin sem MFA é bloqueado pelo BACKEND, não só pela tela")
    void admin_sem_mfa_e_bloqueado() throws Exception {
        // Se isto fosse só uma flag em /me/capabilities, quem chamasse a API diretamente
        // entraria sem segundo fator nenhum — o próprio projeto diz que esconder botão é UX.
        String token = entrar();

        assertThat(get(ROTA_PROTEGIDA, token).statusCode())
                .as("403: barrado pela autorização")
                .isEqualTo(403);
    }

    @Test
    @DisplayName("mas alcança o que precisa para sair do bloqueio")
    void caminho_de_saida_permanece_aberto() throws Exception {
        // Bloquear /me/capabilities e a inscrição deixaria o usuário trancado sem ter como
        // se inscrever, nem informação para a tela explicar o motivo.
        String token = entrar();

        assertThat(get("/api/v1/me/capabilities", token).statusCode()).isEqualTo(200);
        assertThat(post("/api/v1/auth/mfa/inscrever", "", token).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("depois de confirmar o MFA, o acesso é liberado na hora")
    void confirmar_mfa_libera_o_acesso() throws Exception {
        String token = entrar();
        var inscricao = post("/api/v1/auth/mfa/inscrever", "", token);
        String segredo = extrair(inscricao.body(), "segredo");

        var confirmacao = post("/api/v1/auth/mfa/confirmar",
                "{\"codigo\":\"" + codigo(segredo) + "\"}", token);
        assertThat(confirmacao.statusCode()).isEqualTo(200);

        // A confirmação devolve tokens novos justamente para não deixar o usuário bloqueado
        // logo depois de fazer o que se pediu dele.
        String tokenNovo = extrair(confirmacao.body(), "tokenDeAcesso");
        assertThat(tokenNovo).isNotEqualTo(token);

        assertThat(get(ROTA_PROTEGIDA, tokenNovo).statusCode())
                .as("404: passou pela autorização e só não achou o controller")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("quem não precisa de MFA passa direto")
    void recepcao_nao_precisa_de_mfa() throws Exception {
        rebaixarParaRecepcao();
        String token = entrar();

        assertThat(get(ROTA_PROTEGIDA, token).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("sem token, nada passa")
    void sem_token_nada_passa() throws Exception {
        assertThat(get(ROTA_PROTEGIDA, null).statusCode()).isIn(401, 403);
        assertThat(get("/api/v1/me/capabilities", null).statusCode()).isIn(401, 403);
    }

    private String codigo(String segredo) {
        // Janela seguinte: evita que o código expire entre gerar e a requisição chegar.
        return Totp.gerar(segredo, Totp.contadorDe(Instant.now()) + 1);
    }

    private String entrar() throws Exception {
        var resposta = post("/api/v1/auth/login",
                "{\"email\":\"ana@salao.test\",\"senha\":\"" + SENHA + "\"}", null);
        return extrair(resposta.body(), "tokenDeAcesso");
    }

    private void rebaixarParaRecepcao() throws SQLException {
        try (var ps = comoOwner().prepareStatement("update usuario set perfil = ?")) {
            ps.setString(1, Perfil.RECEPCAO.name());
            ps.executeUpdate();
        }
    }

    private String extrair(String json, String campo) {
        var m = Pattern.compile("\"" + campo + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        assertThat(m.find()).as("campo %s em %s", campo, json).isTrue();
        return m.group(1);
    }

    private HttpResponse<String> get(String caminho, String token) throws Exception {
        return enviar(HttpRequest.newBuilder().GET(), caminho, token);
    }

    private HttpResponse<String> post(String caminho, String corpo, String token) throws Exception {
        return enviar(HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(corpo))
                .header("Content-Type", "application/json"), caminho, token);
    }

    private HttpResponse<String> enviar(HttpRequest.Builder construtor, String caminho,
                                        String token) throws Exception {
        construtor.uri(URI.create("http://localhost:" + porta + caminho))
                .timeout(Duration.ofSeconds(10));
        if (token != null) {
            construtor.header("Authorization", "Bearer " + token);
        }
        return http.send(construtor.build(), HttpResponse.BodyHandlers.ofString());
    }
}
