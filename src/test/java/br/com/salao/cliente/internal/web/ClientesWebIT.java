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
    @DisplayName("busca por 4 dígitos acha quem está ligando, num parâmetro só")
    void busca_pelos_quatro_digitos() throws Exception {
        // Um parâmetro, não dois. Obrigar a escolher "por nome" ou "por telefone" seria pedir uma
        // decisão a quem está com o telefone tocando na outra mão.
        String token = entrar();
        post("/api/v1/clientes", "{\"nome\":\"Maria Silva\",\"telefone\":\"11987654321\"}", token);

        var resposta = get("/api/v1/clientes?q=4321", token);

        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(resposta.body()).contains("Maria Silva");
    }

    @Test
    @DisplayName("termo curto devolve lista vazia com 200, não erro")
    void termo_curto_devolve_vazio() throws Exception {
        // 422 a cada tecla faria a tela piscar um erro enquanto a pessoa ainda digita a primeira
        // letra. Vazio é a resposta certa, e a tela explica o que falta.
        String token = entrar();

        var resposta = get("/api/v1/clientes?q=j", token);

        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(resposta.body()).isEqualTo("[]");
    }

    @Test
    @DisplayName("sem q nenhum, não despeja a base inteira")
    void sem_termo_nao_lista_tudo() throws Exception {
        // O endpoint de busca não é o de listagem (RT-CLI-006). Devolver todo mundo aqui seria
        // entregar a base de clientes a quem só sabe chamar a rota sem parâmetro.
        String token = entrar();
        post("/api/v1/clientes", "{\"nome\":\"Maria Silva\"}", token);

        assertThat(get("/api/v1/clientes", token).body()).isEqualTo("[]");
    }

    @Test
    @DisplayName("os possíveis duplicados vêm com o grau, para a tela saber o que dizer")
    void possiveis_duplicados_trazem_o_grau() throws Exception {
        // PROVAVEL pede "é esta pessoa?"; MESMO_TELEFONE é só contexto e não pode parecer erro.
        String token = entrar();
        post("/api/v1/clientes", "{\"nome\":\"Maria Silva\",\"telefone\":\"11987654321\"}", token);

        var provavel = get("/api/v1/clientes/possiveis-duplicados"
                + "?nome=Maria%20da%20Silva&telefone=5511987654321", token);
        assertThat(provavel.statusCode()).isEqualTo(200);
        assertThat(provavel.body()).contains("PROVAVEL").contains("Maria Silva");

        var familia = get("/api/v1/clientes/possiveis-duplicados"
                + "?nome=J%C3%BAlia%20Silva&telefone=5511987654321", token);
        assertThat(familia.body()).contains("MESMO_TELEFONE");
    }

    @Test
    @DisplayName("mesclar devolve a ficha que ficou, e o id antigo continua resolvendo")
    void mesclar_pelo_http() throws Exception {
        String token = entrar();
        String fica = idDe(post("/api/v1/clientes",
                "{\"nome\":\"Maria Silva\",\"telefone\":\"11987654321\"}", token));
        String sai = idDe(post("/api/v1/clientes", "{\"nome\":\"Maria da Silva\"}", token));

        var resposta = post("/api/v1/clientes/" + fica + "/mesclar",
                "{\"absorvidaId\":\"" + sai + "\"}", token);

        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(resposta.body()).contains(fica);
        // O id antigo continua achando a pessoa: é disso que dependem agendamento e comanda.
        assertThat(get("/api/v1/clientes/" + sai, token).body()).contains(fica);
    }

    @Test
    @DisplayName("mesclar sem corpo válido é recusado na borda")
    void mesclar_sem_corpo_e_recusado() throws Exception {
        String token = entrar();
        String fica = idDe(post("/api/v1/clientes", "{\"nome\":\"Maria Silva\"}", token));

        assertThat(post("/api/v1/clientes/" + fica + "/mesclar", "{}", token).statusCode())
                .isIn(400, 422);
    }

    @Test
    @DisplayName("consentimento: conceder e revogar pela mesma rota")
    void consentimento_pelo_http() throws Exception {
        // A LGPD exige que revogar seja tão fácil quanto consentir (art. 8 §5). A forma confiável
        // de cumprir isso não é lembrar de facilitar o "não" — é não ter dois caminhos.
        String token = entrar();
        String cliente = idDe(post("/api/v1/clientes", "{\"nome\":\"Maria Silva\"}", token));
        String rota = "/api/v1/clientes/" + cliente + "/consentimentos";

        var inicial = get(rota, token);
        assertThat(inicial.statusCode()).isEqualTo(200);
        assertThat(inicial.body()).contains("SEM_REGISTRO");

        var concedeu = put(rota + "/MARKETING",
                "{\"concedido\":true,\"canal\":\"BALCAO\"}", token);
        assertThat(concedeu.statusCode()).isEqualTo(200);
        assertThat(concedeu.body()).contains("CONCEDIDO");

        var revogou = put(rota + "/MARKETING",
                "{\"concedido\":false,\"canal\":\"WHATSAPP\"}", token);
        assertThat(revogou.body()).contains("RECUSADO");
    }

    @Test
    @DisplayName("parâmetro de tipo errado na URL vira 400, não 500")
    void parametro_de_tipo_errado_nao_e_500() throws Exception {
        // Encontrado ao escrever este teste, e não era só do consentimento: QUALQUER rota com
        // parâmetro tipado devolvia 500, porque as rotas com id só eram exercitadas com ids
        // válidos. 500 diz "algo quebrou aqui dentro" quando quem errou foi quem chamou — e
        // enche o log de erro interno, escondendo os 500 de verdade.
        String token = entrar();
        String cliente = idDe(post("/api/v1/clientes", "{\"nome\":\"Maria Silva\"}", token));

        var finalidadeInvalida = put(
                "/api/v1/clientes/" + cliente + "/consentimentos/QUALQUER_COISA",
                "{\"concedido\":true,\"canal\":\"BALCAO\"}", token);
        assertThat(finalidadeInvalida.statusCode()).isEqualTo(400);
        assertThat(finalidadeInvalida.body()).contains("ER-INF-DADOS_INVALIDOS");

        var idInvalido = get("/api/v1/clientes/nao-e-uuid", token);
        assertThat(idInvalido.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("a recepção não anonimiza: DELETE exige permissão própria")
    void recepcao_nao_anonimiza() throws Exception {
        // A recepção tem cliente:write e cadastra o dia inteiro. Apagar o dado de alguém para
        // sempre é outro tipo de ato, e não deve estar a um clique de quem atende o balcão.
        String token = entrar();
        String cliente = idDe(post("/api/v1/clientes", "{\"nome\":\"Maria Silva\"}", token));

        var recusa = delete("/api/v1/clientes/" + cliente, token);
        assertThat(recusa.statusCode())
                .as("403, e não 500: negar permissão é desfecho normal, não falha do servidor")
                .isEqualTo(403);
        assertThat(recusa.body()).contains("ER-INF-ACESSO_NEGADO");
        assertThat(get("/api/v1/clientes/" + cliente, token).statusCode())
                .as("e a ficha continua lá").isEqualTo(200);
    }

    @Test
    @DisplayName("sem token, o cadastro não abre")
    void sem_token_nao_cadastra() throws Exception {
        assertThat(post("/api/v1/clientes", "{\"nome\":\"Maria\"}", null).statusCode())
                .isIn(401, 403);
    }

    /** O id sai do cabeçalho Location do 201. */
    private String idDe(HttpResponse<String> criacao) {
        String local = criacao.headers().firstValue("Location").orElseThrow();
        return local.substring(local.lastIndexOf('/') + 1);
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

    private HttpResponse<String> delete(String caminho, String token) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho)).DELETE();
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
