package br.com.salao.atendimento.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.catalogo.api.TipoDeBloco;
import br.com.salao.catalogo.internal.application.CadastrarServicoUseCase;
import br.com.salao.catalogo.internal.application.DefinirPrecoUseCase;
import br.com.salao.catalogo.internal.application.EtapaBruta;
import br.com.salao.equipe.api.TipoDeVinculo;
import br.com.salao.estoque.api.FinalidadeDoProduto;
import br.com.salao.estoque.api.UnidadeDeMedida;
import br.com.salao.estoque.internal.application.CadastrarProdutoUseCase;
import br.com.salao.estoque.internal.application.DadosDaEntrada;
import br.com.salao.estoque.internal.application.DadosDoProduto;
import br.com.salao.estoque.internal.application.RegistrarEntradaDeLoteUseCase;
import br.com.salao.equipe.internal.application.CadastrarProfissionalUseCase;
import br.com.salao.equipe.internal.application.DadosDoProfissional;
import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

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

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarProfissionalUseCase cadastrarProfissional;
    @Autowired private CadastrarServicoUseCase cadastrarServico;
    @Autowired private DefinirPrecoUseCase definirPreco;
    @Autowired private CadastrarProdutoUseCase cadastrarProduto;
    @Autowired private RegistrarEntradaDeLoteUseCase entradaDeLote;

    private UUID tenant;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void criarSalaoComRecepcao() throws SQLException {
        tenant = provisionar.executar(new ProvisionarEstabelecimentoCommand(
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


    /**
     * Cadastro que só o dono faz, montado por dentro.
     *
     * <p>Pela porta HTTP exigiria entrar como ADMIN, e ADMIN exige segundo fator — que já tem
     * teste próprio. O que este arquivo protege é o contrato da comanda, não o do cadastro.
     */
    private <T> T comoDono(Supplier<T> acao) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("dona", null, List.of(
                        new SimpleGrantedAuthority(Permissao.CONFIG_GERENCIAR),
                        new SimpleGrantedAuthority(Permissao.USUARIO_GERENCIAR),
                        new SimpleGrantedAuthority(Permissao.ESTOQUE_ESCREVER),
                        new SimpleGrantedAuthority(Permissao.PRODUTO_PRECO_ESCREVER))));
        try {
            return TenantContext.obter(tenant, acao::get);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private UUID umaProfissional() {
        return comoDono(() -> cadastrarProfissional.executar(new DadosDoProfissional(
                "Ana Ribeiro", "Ana", "11144477735", TipoDeVinculo.EMPREGADO, null, null,
                new BigDecimal("40.00"), null, null, null))).id();
    }

    private UUID umServicoDe80() {
        UUID servico = comoDono(() -> cadastrarServico.executar("Escova", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 45)))).id();
        comoDono(() -> definirPreco.executar(servico, Money.de("80"), LocalDate.now()));
        return servico;
    }

    private String abrirComandaDe(String clienteId, String token) throws Exception {
        var criada = post("/api/v1/comandas", "{\"clienteId\":\"" + clienteId + "\"}", token);
        String local = criada.headers().firstValue("Location").orElseThrow();
        return local.substring(local.lastIndexOf('/') + 1);
    }

    @Test
    @DisplayName("lançar item devolve a comanda inteira, com o total novo")
    void lancar_item_devolve_a_comanda() throws Exception {
        // Devolver só o item obrigaria o front a somar por conta — e duas somas, a da tela e a do
        // banco, divergem no dia em que alguém arredondar diferente.
        String token = entrar();
        String comanda = abrirComandaDe(umaCliente(token), token);
        UUID servico = umServicoDe80();
        UUID profissional = umaProfissional();

        var resposta = post("/api/v1/comandas/" + comanda + "/itens",
                "{\"servicoId\":\"" + servico + "\",\"profissionalId\":\"" + profissional
                        + "\"}", token);

        assertThat(resposta.statusCode()).isEqualTo(201);
        assertThat(resposta.body()).contains("Escova").contains("80.0000")
                .contains("PRINCIPAL").contains("PROFISSIONAL");
    }

    @Test
    @DisplayName("a comanda detalhada traz os itens; remover devolve o total ao que era")
    void detalhe_e_remocao() throws Exception {
        String token = entrar();
        String comanda = abrirComandaDe(umaCliente(token), token);
        post("/api/v1/comandas/" + comanda + "/itens",
                "{\"servicoId\":\"" + umServicoDe80() + "\",\"profissionalId\":\""
                        + umaProfissional() + "\"}", token);

        var detalhe = get("/api/v1/comandas/" + comanda, token);
        assertThat(detalhe.statusCode()).isEqualTo(200);
        assertThat(detalhe.body()).contains("Escova");

        String itemId = detalhe.body().replaceAll("(?s).*\"itens\":\\[\\{\"id\":\"([^\"]+)\".*", "$1");
        var apos = delete("/api/v1/comandas/" + comanda + "/itens/" + itemId, token);

        assertThat(apos.statusCode()).isEqualTo(200);
        assertThat(apos.body()).contains("\"itens\":[]");
    }

    @Test
    @DisplayName("item sem serviço nem profissional para na borda")
    void item_incompleto() throws Exception {
        String token = entrar();
        String comanda = abrirComandaDe(umaCliente(token), token);

        assertThat(post("/api/v1/comandas/" + comanda + "/itens", "{}", token).statusCode())
                .isIn(400, 422);
    }

    @Test
    @DisplayName("comanda que não existe responde 404")
    void comanda_inexistente() throws Exception {
        assertThat(get("/api/v1/comandas/" + UUID.randomUUID(), entrar()).statusCode())
                .isEqualTo(404);
    }


    /** Abre a comanda e lança um serviço de R$ 80,00. Devolve o id do item. */
    private String comandaComItemDe80(String comanda, String token) throws Exception {
        var resposta = post("/api/v1/comandas/" + comanda + "/itens",
                "{\"servicoId\":\"" + umServicoDe80() + "\",\"profissionalId\":\""
                        + umaProfissional() + "\"}", token);
        return resposta.body().replaceAll(
                "(?s).*\"itens\":\\[\\{\"id\":\"([^\"]+)\".*", "$1");
    }

    @Test
    @DisplayName("a recepção desconta dentro do teto dela")
    void desconto_dentro_do_teto() throws Exception {
        String token = entrar();
        String comanda = abrirComandaDe(umaCliente(token), token);
        String item = comandaComItemDe80(comanda, token);

        var resposta = put("/api/v1/comandas/" + comanda + "/itens/" + item + "/desconto",
                "{\"percentual\":10}", token);

        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(resposta.body()).contains("72.0000");
    }

    @Test
    @DisplayName("acima do teto o servidor recusa com 403, mesmo o front sabendo o limite")
    void desconto_acima_do_teto() throws Exception {
        // Capacidades já entrega descontoMaximoPercentual ao front, que esconde o campo. Isso é
        // usabilidade — quem chama a rota direto continua tendo de passar por aqui.
        String token = entrar();
        String comanda = abrirComandaDe(umaCliente(token), token);
        String item = comandaComItemDe80(comanda, token);

        var resposta = put("/api/v1/comandas/" + comanda + "/itens/" + item + "/desconto",
                "{\"percentual\":40}", token);

        assertThat(resposta.statusCode()).isEqualTo(403);
        assertThat(resposta.body()).contains("ER-ATD-DESCONTO_ACIMA_DO_LIMITE");
    }

    @Test
    @DisplayName("valor e percentual juntos param na borda")
    void desconto_ambiguo() throws Exception {
        String token = entrar();
        String comanda = abrirComandaDe(umaCliente(token), token);
        String item = comandaComItemDe80(comanda, token);

        assertThat(put("/api/v1/comandas/" + comanda + "/itens/" + item + "/desconto",
                "{\"valor\":5,\"percentual\":10}", token).statusCode()).isIn(400, 422);
    }



    /**
     * Um shampoo de R$ 45,00 <strong>com estoque</strong>.
     *
     * <p>A entrada de lote faz parte do fixture desde que RT-ATD-006 passou a baixar no
     * fechamento: sem ela o produto é vendável e não fecha, porque não há o que tirar do armário.
     */
    private UUID umShampooDe45() {
        UUID produto = comoDono(() -> cadastrarProduto.executar(new DadosDoProduto(
                "Shampoo", null, FinalidadeDoProduto.VENDA, UnidadeDeMedida.UN,
                Money.de("45")))).id();
        comoDono(() -> entradaDeLote.executar(produto, new DadosDaEntrada(
                new BigDecimal("50"), Money.de("20"), null, null, null)));
        return produto;
    }

    @Test
    @DisplayName("produto entra por rota própria, sem executante")
    void produto_na_comanda() throws Exception {
        // Rota separada da de serviço porque os dois pedem coisas diferentes: um corpo só teria
        // metade dos campos ignorados conforme o tipo, e a validação viraria um if.
        String token = entrar();
        String comanda = abrirComandaDe(umaCliente(token), token);

        var resposta = post("/api/v1/comandas/" + comanda + "/produtos",
                "{\"produtoId\":\"" + umShampooDe45() + "\",\"quantidade\":2}", token);

        assertThat(resposta.statusCode()).isEqualTo(201);
        assertThat(resposta.body()).contains("\"tipo\":\"PRODUTO\"").contains("Shampoo")
                .contains("90.0000").contains("\"executantes\":[]");
    }

    @Test
    @DisplayName("quantidade zero para na borda")
    void produto_quantidade_zero() throws Exception {
        String token = entrar();
        String comanda = abrirComandaDe(umaCliente(token), token);

        assertThat(post("/api/v1/comandas/" + comanda + "/produtos",
                "{\"produtoId\":\"" + umShampooDe45() + "\",\"quantidade\":0}", token)
                .statusCode()).isIn(400, 422);
    }


    @Test
    @DisplayName("split: cada pagamento devolve o restante, e o último zera")
    void split_de_pagamento() throws Exception {
        // R-UX-08: split é requisito, não extra. E o restante vem pronto do servidor — subtrair no
        // front seria a mesma conta em dois lugares.
        String token = entrar();
        String comanda = abrirComandaDe(umaCliente(token), token);
        post("/api/v1/comandas/" + comanda + "/produtos",
                "{\"produtoId\":\"" + umShampooDe45() + "\",\"quantidade\":2}", token);

        var primeiro = post("/api/v1/comandas/" + comanda + "/pagamentos",
                "{\"forma\":\"CREDITO\",\"valor\":50,\"bandeira\":\"VISA\"}", token);
        assertThat(primeiro.statusCode()).isEqualTo(201);
        assertThat(primeiro.body()).contains("\"restante\":{\"valor\":40.0000}");

        var segundo = post("/api/v1/comandas/" + comanda + "/pagamentos",
                "{\"forma\":\"PIX\",\"valor\":40}", token);
        assertThat(segundo.body()).contains("\"restante\":{\"valor\":0.0000}");

        var conta = get("/api/v1/comandas/" + comanda + "/conta", token);
        assertThat(conta.statusCode()).isEqualTo(200);
        assertThat(conta.body()).contains("CREDITO").contains("PIX").contains("VISA");
    }

    @Test
    @DisplayName("pagamento acima do que falta é recusado")
    void pagamento_passa_do_total() throws Exception {
        String token = entrar();
        String comanda = abrirComandaDe(umaCliente(token), token);
        post("/api/v1/comandas/" + comanda + "/produtos",
                "{\"produtoId\":\"" + umShampooDe45() + "\",\"quantidade\":1}", token);

        var resposta = post("/api/v1/comandas/" + comanda + "/pagamentos",
                "{\"forma\":\"DINHEIRO\",\"valor\":100}", token);

        assertThat(resposta.statusCode()).isEqualTo(422);
        assertThat(resposta.body()).contains("ER-ATD-PAGAMENTO_PASSA_DO_TOTAL");
    }


    @Test
    @DisplayName("fechar responde a comanda FECHADA; com saldo aberto, 422")
    void fechamento_pelo_http() throws Exception {
        String token = entrar();
        String comanda = abrirComandaDe(umaCliente(token), token);
        post("/api/v1/comandas/" + comanda + "/produtos",
                "{\"produtoId\":\"" + umShampooDe45() + "\",\"quantidade\":1}", token);

        var semPagar = post("/api/v1/comandas/" + comanda + "/fechamento", "", token);
        assertThat(semPagar.statusCode()).isEqualTo(422);
        assertThat(semPagar.body()).contains("ER-ATD-COMANDA_COM_SALDO_ABERTO");

        post("/api/v1/comandas/" + comanda + "/pagamentos",
                "{\"forma\":\"DINHEIRO\",\"valor\":45}", token);
        var fechada = post("/api/v1/comandas/" + comanda + "/fechamento", "", token);

        assertThat(fechada.statusCode()).isEqualTo(200);
        assertThat(fechada.body()).contains("\"status\":\"FECHADA\"");
        assertThat(get("/api/v1/comandas/abertas", token).body()).contains("[]");
    }

    private UUID umaAssistente() {
        return comoDono(() -> cadastrarProfissional.executar(new DadosDoProfissional(
                "Bia Nunes", "Bia", "52998224725", TipoDeVinculo.EMPREGADO, null, null,
                new BigDecimal("45.00"), null, null, null))).id();
    }

    @Test
    @DisplayName("o assistente entra e sai do item pela mesma comanda")
    void assistente_entra_e_sai() throws Exception {
        String token = entrar();
        String comanda = abrirComandaDe(umaCliente(token), token);
        String item = comandaComItemDe80(comanda, token);

        var entrada = post("/api/v1/comandas/" + comanda + "/itens/" + item + "/executantes",
                "{\"profissionalId\":\"" + umaAssistente() + "\",\"percentual\":10}", token);

        assertThat(entrada.statusCode()).isEqualTo(201);
        assertThat(entrada.body()).contains("ASSISTENTE").contains("INFORMADA_NO_ITEM");

        var achado = Pattern.compile("\\{\"id\":\"([^\"]+)\",\"profissionalId\":\"[^\"]+\","
                + "\"papel\":\"ASSISTENTE\"").matcher(entrada.body());
        assertThat(achado.find()).as("o assistente veio na resposta").isTrue();
        String executante = achado.group(1);
        var saida = delete("/api/v1/comandas/" + comanda + "/itens/" + item
                + "/executantes/" + executante, token);

        assertThat(saida.statusCode()).isEqualTo(200);
        assertThat(saida.body()).doesNotContain("ASSISTENTE");
    }

    @Test
    @DisplayName("assistente sem percentual para na borda")
    void assistente_sem_percentual() throws Exception {
        String token = entrar();
        String comanda = abrirComandaDe(umaCliente(token), token);
        String item = comandaComItemDe80(comanda, token);

        assertThat(post("/api/v1/comandas/" + comanda + "/itens/" + item + "/executantes",
                "{\"profissionalId\":\"" + umaAssistente() + "\"}", token).statusCode())
                .isIn(400, 422);
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
