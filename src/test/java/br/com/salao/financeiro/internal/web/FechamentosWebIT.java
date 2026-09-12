package br.com.salao.financeiro.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.equipe.api.TipoDeVinculo;
import br.com.salao.equipe.internal.application.CadastrarProfissionalUseCase;
import br.com.salao.equipe.internal.application.DadosDoProfissional;
import br.com.salao.financeiro.api.TipoDeLancamento;
import br.com.salao.financeiro.internal.infra.LancamentosJdbc;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.iam.internal.domain.Totp;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** RT-FIN-006 — o contrato HTTP da prévia, confirmação e consulta do fechamento. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@WithMockUser(authorities = Permissao.USUARIO_GERENCIAR)
class FechamentosWebIT extends AbstractPostgresIT {

    private static final String EMAIL = "dona@salao.test";
    private static final String SENHA = "senha-bem-comprida-1";

    @DynamicPropertySource
    static void semLimiteDeTaxa(DynamicPropertyRegistry registro) {
        registro.add("app.rede.limite-autenticacao", () -> "1000");
    }

    @Value("${local.server.port}")
    private int porta;

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarProfissionalUseCase cadastrarProfissional;
    @Autowired private LancamentosJdbc lancamentos;
    @Autowired private Relogio relogio;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private UUID tenant;
    private LocalDate hoje;

    @BeforeEach
    void criarCenario() {
        tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona", EMAIL, SENHA));
        hoje = TenantContext.obter(tenant,
                () -> relogio.hojeEm(java.time.ZoneId.of("America/Sao_Paulo")));
        UUID profissional = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional("Ana Ribeiro", "Ana", "11144477735",
                        TipoDeVinculo.EMPREGADO, null, null, new BigDecimal("30"),
                        null, null, null))).id();
        TenantContext.executar(tenant, () -> lancamentos.inserir(tenant, hoje,
                TipoDeLancamento.COMISSAO, "COMANDA", UUID.randomUUID(), "PROFISSIONAL",
                profissional, Money.de("60").negado(), "Comissão de teste", null,
                relogio.agora(), null));
    }

    @Test
    @DisplayName("prévia, confirmação e consulta preservam os números pela porta HTTP")
    void fluxo_completo() throws Exception {
        String token = entrarComMfa();
        String query = "?de=" + hoje.minusDays(5) + "&ate=" + hoje;

        var previa = get("/api/v1/fechamentos/previa" + query, token);
        assertThat(previa.statusCode()).isEqualTo(200);
        assertThat(previa.body()).contains("\"profissionalNome\":\"Ana\"")
                .contains("\"totalARepassar\":{\"valor\":60.0000}");
        String versao = extrair(previa.body(), "versao");

        var confirmacao = post("/api/v1/fechamentos", """
                {"de":"%s","ate":"%s","versaoDaPrevia":"%s"}
                """.formatted(hoje.minusDays(5), hoje, versao), token);
        assertThat(confirmacao.statusCode()).isEqualTo(201);
        assertThat(confirmacao.body()).contains("\"profissionalNome\":\"Ana\"")
                .contains("\"valorRepassado\":{\"valor\":60.0000}");

        var lista = get("/api/v1/fechamentos" + query, token);
        assertThat(lista.statusCode()).isEqualTo(200);
        assertThat(lista.body()).contains("\"profissionalNome\":\"Ana\"")
                .contains("\"saldoATransportar\":{\"valor\":0.0000}");
    }

    @Test
    @DisplayName("a confirmação sem a versão da prévia para na borda")
    void versao_ausente() throws Exception {
        var resposta = post("/api/v1/fechamentos",
                "{\"de\":\"" + hoje.minusDays(5) + "\",\"ate\":\"" + hoje + "\"}",
                entrarComMfa());

        assertThat(resposta.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("administrador sem segundo fator não vê salários pela prévia")
    void mfa_pendente() throws Exception {
        var resposta = get("/api/v1/fechamentos/previa?de=" + hoje.minusDays(5)
                + "&ate=" + hoje, entrar());

        assertThat(resposta.statusCode()).isEqualTo(403);
    }

    private String entrarComMfa() throws Exception {
        String token = entrar();
        var inscricao = post("/api/v1/auth/mfa/inscrever", "", token);
        String segredo = extrair(inscricao.body(), "segredo");
        var confirmacao = post("/api/v1/auth/mfa/confirmar",
                "{\"codigo\":\"" + codigo(segredo) + "\"}", token);
        assertThat(confirmacao.statusCode()).isEqualTo(200);
        return extrair(confirmacao.body(), "tokenDeAcesso");
    }

    private String entrar() throws Exception {
        var resposta = post("/api/v1/auth/login",
                "{\"email\":\"" + EMAIL + "\",\"senha\":\"" + SENHA + "\"}", null);
        assertThat(resposta.statusCode()).isEqualTo(200);
        return extrair(resposta.body(), "tokenDeAcesso");
    }

    private String codigo(String segredo) {
        return Totp.gerar(segredo, Totp.contadorDe(Instant.now()) + 1);
    }

    private String extrair(String json, String campo) {
        var matcher = Pattern.compile("\"" + campo + "\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(json);
        assertThat(matcher.find()).as("campo %s em %s", campo, json).isTrue();
        return matcher.group(1);
    }

    private HttpResponse<String> get(String caminho, String token) throws Exception {
        return enviar(HttpRequest.newBuilder().GET(), caminho, token);
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
