package br.com.salao.iam.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/** RT-IAM-002/003 — o contrato HTTP da autenticação. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AutenticacaoWebIT extends AbstractPostgresIT {

    private static final String SENHA = "senha-bem-comprida-1";

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
    @DisplayName("o login devolve o refresh em cookie protegido, nunca no corpo")
    void login_devolve_cookie_protegido() throws Exception {
        var resposta = post("/api/v1/auth/login",
                "{\"email\":\"ana@salao.test\",\"senha\":\"" + SENHA + "\"}", null);

        assertThat(resposta.statusCode()).isEqualTo(200);

        String cookie = cabecalhoDeCookie(resposta);
        assertThat(cookie)
                .as("HttpOnly impede que um XSS leve embora o token de trinta dias")
                .contains("HttpOnly")
                .as("SameSite=Strict é a proteção contra CSRF neste desenho")
                .contains("SameSite=Strict")
                .as("Secure: não trafega em texto claro")
                .contains("Secure")
                .as("Path restrito: o cookie não vai para os demais endpoints")
                .contains("Path=/api/v1/auth");

        assertThat(resposta.body())
                .as("o refresh no corpo anularia o HttpOnly")
                .doesNotContain("refresh")
                .contains("tokenDeAcesso");
    }

    @Test
    @DisplayName("o refresh rotaciona pelo cookie e entrega um cookie novo")
    void refresh_rotaciona_pelo_cookie() throws Exception {
        var login = post("/api/v1/auth/login",
                "{\"email\":\"ana@salao.test\",\"senha\":\"" + SENHA + "\"}", null);
        String cookieInicial = valorDoCookie(login);

        var renovacao = post("/api/v1/auth/refresh", "", cookieInicial);

        assertThat(renovacao.statusCode()).isEqualTo(200);
        assertThat(valorDoCookie(renovacao))
                .as("uso único: o cookie precisa mudar a cada renovação")
                .isNotEqualTo(cookieInicial);
    }

    @Test
    @DisplayName("refresh sem cookie é recusado com o código do catálogo")
    void refresh_sem_cookie() throws Exception {
        var resposta = post("/api/v1/auth/refresh", "", null);

        assertThat(resposta.statusCode()).isEqualTo(401);
        assertThat(resposta.body()).contains("ER-IAM-SESSAO_EXPIRADA");
    }

    @Test
    @DisplayName("endpoint novo nasce protegido")
    void rota_desconhecida_exige_autenticacao() throws Exception {
        // anyRequest().authenticated(): abrir é ato deliberado. O contrário — abrir por padrão
        // e lembrar de fechar — falha na primeira distração.
        var resposta = post("/api/v1/agendamentos", "{}", null);

        assertThat(resposta.statusCode()).isIn(401, 403);
    }

    private String cabecalhoDeCookie(HttpResponse<String> resposta) {
        return resposta.headers().firstValue("set-cookie").orElseThrow();
    }

    private String valorDoCookie(HttpResponse<String> resposta) {
        String cabecalho = cabecalhoDeCookie(resposta);
        return cabecalho.substring(0, cabecalho.indexOf(';'));
    }

    private HttpResponse<String> post(String caminho, String corpo, String cookie)
            throws Exception {
        var construtor = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + porta + caminho))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(corpo));
        if (cookie != null) {
            construtor.header("Cookie", cookie);
        }
        return http.send(construtor.build(), HttpResponse.BodyHandlers.ofString());
    }
}
