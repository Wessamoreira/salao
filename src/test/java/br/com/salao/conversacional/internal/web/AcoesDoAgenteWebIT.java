package br.com.salao.conversacional.internal.web;

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
import java.util.List;
import java.util.UUID;
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
 * RT-CNV-007 — o contrato HTTP do painel de revisão.
 *
 * <p>O que só a porta HTTP mostra: quem passa pela autorização, como os filtros tipados chegam, e o
 * que o JSON expõe — os argumentos como objeto, e o telefone só pelos quatro últimos dígitos.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AcoesDoAgenteWebIT extends AbstractPostgresIT {

    private static final String EMAIL = "dona@salao.test";
    private static final String SENHA = "senha-bem-comprida-1";
    private static final String ROTA = "/api/v1/agente/acoes";

    @DynamicPropertySource
    static void semLimiteDeTaxa(DynamicPropertyRegistry registro) {
        registro.add("app.rede.limite-autenticacao", () -> "1000");
    }

    @Value("${local.server.port}")
    private int porta;

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private UUID tenant;

    @BeforeEach
    void criarSalao() {
        tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona", EMAIL, SENHA));
    }

    @Test
    @DisplayName("a gerente revisa pelo HTTP, e o número de quem escreveu não sai inteiro")
    void gerente_revisa() throws Exception {
        tornar(Perfil.GERENTE);
        tentativaBloqueada();
        String token = entrarComMfa();

        var lista = get(ROTA + "?recorte=BLOQUEADAS", token);
        var resumo = get(ROTA + "/resumo", token);
        var pessoas = get(ROTA + "/pessoas", token);

        assertThat(lista.statusCode()).isEqualTo(200);
        assertThat(lista.body())
                .contains("\"telefoneFinal\":\"4321\"")
                .contains("\"argumentos\":{\"dia\":\"2026-09-20\"}")
                .contains("\"temMais\":false")
                .doesNotContain("5511987654321");
        assertThat(resumo.statusCode()).isEqualTo(200);
        assertThat(resumo.body()).contains("\"conversas\":1").contains("\"bloqueadas\":1");
        assertThat(pessoas.statusCode()).isEqualTo(200);
        assertThat(pessoas.body()).isEqualTo("[]");
    }

    @Test
    @DisplayName("a recepção não abre o painel: nem a lista, nem os totais, nem as pessoas")
    void recepcao_nao_revisa() throws Exception {
        // A recepção conversa com o agente; revisar é ler a conversa dos outros.
        tornar(Perfil.RECEPCAO);
        String token = entrar();

        for (String caminho : List.of(ROTA, ROTA + "/resumo", ROTA + "/pessoas")) {
            var resposta = get(caminho, token);
            assertThat(resposta.statusCode()).as(caminho).isEqualTo(403);
            assertThat(resposta.body()).contains("ER-INF-ACESSO_NEGADO");
        }
    }

    @Test
    @DisplayName("filtro de tipo errado é 400; filtro bem formado e impossível é 422")
    void filtros_na_borda() throws Exception {
        tornar(Perfil.GERENTE);
        String token = entrarComMfa();

        for (String consulta : List.of("?recorte=QUALQUER", "?de=ontem", "?pessoa=nao-e-uuid")) {
            assertThat(get(ROTA + consulta, token).statusCode()).as(consulta).isEqualTo(400);
        }
        var invertido = get(ROTA + "?de=2026-09-10&ate=2026-09-01", token);
        assertThat(invertido.statusCode()).isEqualTo(422);
        assertThat(invertido.body()).contains("ER-CNV-FILTRO_INVALIDO");
        assertThat(get(ROTA + "?cursor=forjado", token).statusCode()).isEqualTo(422);
    }

    @Test
    @DisplayName("sem token, o painel não abre")
    void sem_token() throws Exception {
        assertThat(get(ROTA, null).statusCode()).isIn(401, 403);
    }

    private void tornar(Perfil perfil) throws SQLException {
        try (var ps = comoOwner().prepareStatement("update usuario set perfil = ?")) {
            ps.setString(1, perfil.name());
            ps.executeUpdate();
        }
    }

    private void tentativaBloqueada() throws SQLException {
        try (var ps = comoOwner().prepareStatement("""
                insert into acao_ia (estabelecimento_id, execucao_id, ocorrida_em, tipo, desfecho,
                    motivo_recusa, de_telefone, veio_de_audio, pedido, ferramentas, intencoes,
                    gravou, bloqueada, tokens_entrada, tokens_saida, custo_estimado_usd)
                values (?, gen_random_uuid(), now(), 'CONVERSA', 'CONCLUIDA', null,
                    '5511987654321', false, 'marca pra mim', cast(? as jsonb), '[]', false, true,
                    0, 0, 0)""")) {
            ps.setObject(1, tenant);
            ps.setString(2, """
                    [{"nome":"agendar","argumentos":{"dia":"2026-09-20"},"desfecho":"SEM_PERMISSAO"}]""");
            ps.executeUpdate();
        }
    }

    private String entrarComMfa() throws Exception {
        String token = entrar();
        var inscricao = post("/api/v1/auth/mfa/inscrever", token);
        String segredo = extrair(inscricao.body(), "segredo");
        var confirmacao = post("/api/v1/auth/mfa/confirmar",
                "{\"codigo\":\"" + Totp.gerar(segredo, Totp.contadorDe(Instant.now()) + 1) + "\"}",
                token);
        assertThat(confirmacao.statusCode()).isEqualTo(200);
        return extrair(confirmacao.body(), "tokenDeAcesso");
    }

    private String entrar() throws Exception {
        var resposta = post("/api/v1/auth/login",
                "{\"email\":\"" + EMAIL + "\",\"senha\":\"" + SENHA + "\"}", null);
        assertThat(resposta.statusCode()).isEqualTo(200);
        return extrair(resposta.body(), "tokenDeAcesso");
    }

    private String extrair(String json, String campo) {
        var matcher = Pattern.compile("\"" + campo + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        assertThat(matcher.find()).as("campo %s em %s", campo, json).isTrue();
        return matcher.group(1);
    }

    private HttpResponse<String> get(String caminho, String token) throws Exception {
        return enviar(HttpRequest.newBuilder().GET(), caminho, token);
    }

    private HttpResponse<String> post(String caminho, String token) throws Exception {
        return post(caminho, "", token);
    }

    private HttpResponse<String> post(String caminho, String corpo, String token) throws Exception {
        return enviar(HttpRequest.newBuilder()
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(corpo)), caminho, token);
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
