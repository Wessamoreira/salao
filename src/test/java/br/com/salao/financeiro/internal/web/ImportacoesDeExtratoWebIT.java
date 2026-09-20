package br.com.salao.financeiro.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.iam.internal.domain.Totp;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
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
 * RT-FIN-009 — o contrato HTTP da importação: o primeiro multipart do sistema.
 *
 * <p>O que só a porta HTTP mostra: o arquivo e o mapeamento chegando juntos, e os limites virando o
 * código certo — 413 para arquivo grande, 400 para envio malformado, 422 para planilha —, e não 500.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ImportacoesDeExtratoWebIT extends AbstractPostgresIT {

    private static final String EMAIL = "dona@salao.test";
    private static final String SENHA = "senha-bem-comprida-1";
    private static final String ROTA = "/api/v1/conciliacao/importacoes";
    private static final String CSV = "STONE ID;DATA DA VENDA;VALOR BRUTO;STATUS\n"
            + "ST1;18/09/2026 14:32;1.234,56;Aprovada\n";
    private static final String MAPEAMENTO = """
            {"colunas":{"ID_NA_ADQUIRENTE":"STONE ID","DATA":"DATA DA VENDA",
                        "VALOR_BRUTO":"VALOR BRUTO","SITUACAO":"STATUS"},
             "formato":"BRASILEIRO"}""";

    @DynamicPropertySource
    static void semLimiteDeTaxa(DynamicPropertyRegistry registro) {
        registro.add("app.rede.limite-autenticacao", () -> "1000");
    }

    @Value("${local.server.port}")
    private int porta;

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void criarSalao() {
        provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona", EMAIL, SENHA));
    }

    @Test
    @DisplayName("a gerente vê a prévia e importa: arquivo e mapeamento na mesma requisição")
    void gerente_importa() throws Exception {
        tornar(Perfil.GERENTE);
        String token = entrarComMfa();

        var previa = enviar(ROTA + "/previa", token, arquivo(CSV.getBytes(StandardCharsets.UTF_8)));
        var importacao = enviar(ROTA, token, arquivo(CSV.getBytes(StandardCharsets.UTF_8)),
                mapeamento(MAPEAMENTO));
        var historico = get(ROTA, token);

        assertThat(previa.statusCode()).isEqualTo(200);
        assertThat(previa.body()).contains("\"STONE ID\"").contains("\"lidas\":1")
                .contains("\"valorBruto\":1234.56");
        assertThat(importacao.statusCode()).isEqualTo(201);
        assertThat(importacao.headers().firstValue("Location")).get().asString()
                .startsWith(ROTA + "/");
        assertThat(importacao.body()).contains("\"novas\":1");
        assertThat(historico.body()).contains("\"nomeDoArquivo\":\"vendas.csv\"");
    }

    @Test
    @DisplayName("RT-FIN-010: a conferência pelo HTTP — resumo, lista e \"Conciliar agora\"")
    void conferencia() throws Exception {
        tornar(Perfil.GERENTE);
        String token = entrarComMfa();
        enviar(ROTA, token, arquivo(CSV.getBytes(StandardCharsets.UTF_8)), mapeamento(MAPEAMENTO));

        var resumo = get("/api/v1/conciliacao/resumo?de=2026-09-18&ate=2026-09-18", token);
        var lista = get("/api/v1/conciliacao/itens?recorte=SO_NA_MAQUININHA&de=2026-09-18&ate=2026-09-18",
                token);
        var rodada = postJson("/api/v1/conciliacao/casamentos", "", token);
        var invertido = get("/api/v1/conciliacao/resumo?de=2026-09-18&ate=2026-09-01", token);

        assertThat(resumo.statusCode()).isEqualTo(200);
        assertThat(resumo.body()).contains("\"soNaMaquininha\":{\"quantidade\":1");
        assertThat(lista.body()).contains("\"idNaAdquirente\":\"ST1\"");
        assertThat(rodada.statusCode()).isEqualTo(200);
        assertThat(rodada.body()).contains("\"casadas\":0");
        assertThat(invertido.statusCode()).isEqualTo(422);
        assertThat(invertido.body()).contains("ER-FIN-FILTRO_INVALIDO");
    }

    @Test
    @DisplayName("RT-FIN-011: resolver pede motivo, tira da conferência, e a reabertura devolve")
    void resolucao() throws Exception {
        tornar(Perfil.GERENTE);
        String token = entrarComMfa();
        enviar(ROTA, token, arquivo(CSV.getBytes(StandardCharsets.UTF_8)), mapeamento(MAPEAMENTO));
        String vendaId = extrair(get(
                "/api/v1/conciliacao/itens?recorte=SO_NA_MAQUININHA&de=2026-09-18&ate=2026-09-18",
                token).body(), "vendaId");

        var semMotivo = postJson("/api/v1/conciliacao/resolucoes",
                pedido(vendaId, "LANCAR", ""), token);
        var resolvida = postJson("/api/v1/conciliacao/resolucoes",
                pedido(vendaId, "LANCAR", "venda de produto, sem comanda"), token);
        var deNovo = postJson("/api/v1/conciliacao/resolucoes",
                pedido(vendaId, "LANCAR", "de novo"), token);
        var trilha = get("/api/v1/conciliacao/resolucoes?de=2026-09-18&ate=2026-09-18", token);

        assertThat(semMotivo.statusCode()).isEqualTo(400);
        assertThat(resolvida.statusCode()).isEqualTo(200);
        assertThat(resolvida.body()).contains("\"lancado\":1234.56");
        assertThat(deNovo.statusCode()).isEqualTo(409);
        assertThat(deNovo.body()).contains("ER-FIN-JA_RESOLVIDA");
        assertThat(trilha.body()).contains("\"acao\":\"LANCAR\"")
                .contains("\"motivo\":\"venda de produto, sem comanda\"");
        assertThat(get("/api/v1/conciliacao/resumo?de=2026-09-18&ate=2026-09-18", token).body())
                .contains("\"soNaMaquininha\":{\"quantidade\":0")
                .contains("\"resolvidas\":{\"quantidade\":1");

        String resolucaoId = extrair(resolvida.body(), "id");
        var reaberta = postJson("/api/v1/conciliacao/resolucoes/" + resolucaoId + "/reabertura",
                "{\"motivo\":\"era estorno do dia anterior\"}", token);

        assertThat(reaberta.statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/conciliacao/resumo?de=2026-09-18&ate=2026-09-18", token).body())
                .contains("\"soNaMaquininha\":{\"quantidade\":1");
    }

    private static String pedido(String vendaId, String acao, String motivo) {
        return "{\"alvo\":\"VENDA\",\"id\":\"" + vendaId + "\",\"acao\":\"" + acao
                + "\",\"motivo\":\"" + motivo + "\"}";
    }

    @Test
    @DisplayName("a recepção não importa extrato")
    void recepcao_nao_importa() throws Exception {
        tornar(Perfil.RECEPCAO);
        String token = entrar();

        var resposta = enviar(ROTA + "/previa", token, arquivo(CSV.getBytes(StandardCharsets.UTF_8)));

        assertThat(resposta.statusCode()).isEqualTo(403);
        assertThat(resposta.body()).contains("ER-INF-ACESSO_NEGADO");
        assertThat(get("/api/v1/conciliacao/resumo", token).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("os limites viram o código certo, e nunca 500")
    void limites() throws Exception {
        tornar(Perfil.GERENTE);
        String token = entrarComMfa();

        var grande = enviar(ROTA + "/previa", token, arquivo(new byte[6 * 1024 * 1024]));
        var semArquivo = enviar(ROTA, token, mapeamento(MAPEAMENTO));
        var planilha = enviar(ROTA + "/previa", token, arquivo(new byte[] { 'P', 'K', 3, 4 }));

        assertThat(grande.statusCode()).isEqualTo(413);
        assertThat(grande.body()).contains("ER-INF-ARQUIVO_GRANDE_DEMAIS");
        assertThat(semArquivo.statusCode()).isEqualTo(400);
        assertThat(planilha.statusCode()).isEqualTo(422);
        assertThat(planilha.body()).contains("ER-FIN-ARQUIVO_INVALIDO").contains("CSV");
    }

    // ------------------------------------------------------------------ multipart na mão

    private record Parte(String nome, String nomeDoArquivo, String tipo, byte[] conteudo) {
    }

    private static Parte arquivo(byte[] conteudo) {
        return new Parte("arquivo", "vendas.csv", "text/csv", conteudo);
    }

    private static Parte mapeamento(String json) {
        return new Parte("mapeamento", null, "application/json",
                json.getBytes(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> enviar(String caminho, String token, Parte... partes)
            throws Exception {
        String fronteira = "----salao" + UUID.randomUUID();
        var corpo = new ByteArrayOutputStream();
        for (Parte parte : partes) {
            corpo.write(("--" + fronteira + "\r\nContent-Disposition: form-data; name=\""
                    + parte.nome() + "\""
                    + (parte.nomeDoArquivo() == null ? "" : "; filename=\"" + parte.nomeDoArquivo() + "\"")
                    + "\r\nContent-Type: " + parte.tipo() + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            corpo.write(parte.conteudo());
            corpo.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        corpo.write(("--" + fronteira + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + fronteira)
                .POST(HttpRequest.BodyPublishers.ofByteArray(corpo.toByteArray()))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String caminho, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private void tornar(Perfil perfil) throws SQLException {
        try (var ps = comoOwner().prepareStatement("update usuario set perfil = ?")) {
            ps.setString(1, perfil.name());
            ps.executeUpdate();
        }
    }

    private String entrarComMfa() throws Exception {
        String token = entrar();
        var inscricao = postJson("/api/v1/auth/mfa/inscrever", "", token);
        String segredo = extrair(inscricao.body(), "segredo");
        var confirmacao = postJson("/api/v1/auth/mfa/confirmar",
                "{\"codigo\":\"" + Totp.gerar(segredo, Totp.contadorDe(Instant.now()) + 1) + "\"}",
                token);
        assertThat(confirmacao.statusCode()).isEqualTo(200);
        return extrair(confirmacao.body(), "tokenDeAcesso");
    }

    private String entrar() throws Exception {
        var resposta = postJson("/api/v1/auth/login",
                "{\"email\":\"" + EMAIL + "\",\"senha\":\"" + SENHA + "\"}", null);
        assertThat(resposta.statusCode()).isEqualTo(200);
        return extrair(resposta.body(), "tokenDeAcesso");
    }

    private HttpResponse<String> postJson(String caminho, String corpo, String token)
            throws Exception {
        var construtor = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(corpo));
        if (token != null) {
            construtor.header("Authorization", "Bearer " + token);
        }
        return http.send(construtor.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String extrair(String json, String campo) {
        var matcher = Pattern.compile("\"" + campo + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        assertThat(matcher.find()).as("campo %s em %s", campo, json).isTrue();
        return matcher.group(1);
    }
}
