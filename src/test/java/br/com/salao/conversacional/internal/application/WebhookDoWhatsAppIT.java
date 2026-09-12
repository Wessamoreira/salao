package br.com.salao.conversacional.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.conversacional.api.CanalDoWhatsApp;
import br.com.salao.conversacional.api.MensagemDeClienteRecebida;
import br.com.salao.conversacional.api.TipoDeMensagem;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import tools.jackson.databind.json.JsonMapper;

/** RT-CNV-001 — o webhook do WhatsApp: roteamento, idempotência e isolamento. */
@WithMockUser(authorities = { Permissao.CONFIG_GERENCIAR, Permissao.USUARIO_GERENCIAR })
@RecordApplicationEvents
class WebhookDoWhatsAppIT extends AbstractPostgresIT {

    private static final String NUMERO_A = "1111111111";
    private static final String NUMERO_B = "2222222222";

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private RegistrarCanalDoWhatsAppUseCase registrarCanal;
    @Autowired private ConsultarCanalUseCase consultarCanal;
    @Autowired private ReceberLoteDoWhatsAppUseCase receber;
    @Autowired private ApplicationEvents eventos;

    private final JsonMapper json = JsonMapper.builder().build();

    private UUID salao;

    private UUID novoSalao() {
        return provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
    }

    /** Um salão com o número A ligado. */
    private void cenario() {
        salao = novoSalao();
        TenantContext.executar(salao,
                () -> registrarCanal.executar(NUMERO_A, "5511999990000", true));
    }

    private ReceberLoteDoWhatsAppUseCase.Resumo entregar(String payload) {
        return receber.executar(json.readTree(payload));
    }

    /** Um lote com uma mensagem de texto, parametrizado pelo número de destino e pelo wamid. */
    private String texto(String phoneNumberId, String wamid, String corpo) {
        return """
                {"object":"whatsapp_business_account","entry":[{"id":"WABA","changes":[{
                  "field":"messages","value":{
                    "metadata":{"phone_number_id":"%s"},
                    "messages":[{"from":"5511988887777","id":"%s","timestamp":"1757000000",
                                 "type":"text","text":{"body":"%s"}}]}}]}]}
                """.formatted(phoneNumberId, wamid, corpo);
    }

    /**
     * A caixa de entrada, lida em SQL cru pela role da aplicação.
     *
     * <p>Por {@code comoApp} e não por {@code JdbcClient} dentro de {@code TenantContext}: o
     * escopo do tenant só chega ao banco como {@code SET LOCAL app.tenant_id}, e isso acontece na
     * abertura da TRANSAÇÃO. Sem transação, a consulta roda sem tenant e a RLS filtra tudo —
     * falha fechada, exatamente como projetado. Foi assim que este helper nasceu errado.
     */
    private List<Map<String, Object>> caixaDe(UUID tenant) throws SQLException {
        var linhas = new java.util.ArrayList<Map<String, Object>>();
        var conexao = comoApp(tenant);
        try (var ps = conexao.prepareStatement("""
                select provedor_id, de_telefone, tipo, texto, midia_id, processada_em
                  from mensagem_recebida order by recebida_em
                """);
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                var linha = new java.util.HashMap<String, Object>();
                linha.put("provedor_id", rs.getString("provedor_id"));
                linha.put("de_telefone", rs.getString("de_telefone"));
                linha.put("tipo", rs.getString("tipo"));
                linha.put("texto", rs.getString("texto"));
                linha.put("midia_id", rs.getString("midia_id"));
                linha.put("processada_em", rs.getTimestamp("processada_em"));
                linhas.add(linha);
            }
        }
        /*
         * Rollback ANTES de devolver, e não é zelo: a conexão de `comoApp` é compartilhada e vive
         * com autocommit desligado, então `set_config(..., true)` já abriu transação. Deixá-la
         * aberta faz o `idle_in_transaction_session_timeout` do endurecimento (V11) matar a
         * conexão no meio da classe — e o sintoma foi a suíte passando de 30s para 448s com
         * "terminating connection due to idle-in-transaction timeout" em testes que nada tinham a
         * ver com o anterior.
         */
        conexao.rollback();
        return linhas;
    }

    // ------------------------------------------------------------------ roteamento

    @Test
    @DisplayName("a mensagem cai na caixa do salão dono do número")
    void roteia_pelo_numero() throws SQLException {
        cenario();

        var resumo = entregar(texto(NUMERO_A, "wamid.1", "quero marcar quarta"));

        assertThat(resumo.gravadas()).isEqualTo(1);
        assertThat(caixaDe(salao)).singleElement().satisfies(linha -> {
            assertThat(linha.get("provedor_id")).isEqualTo("wamid.1");
            assertThat(linha.get("texto")).isEqualTo("quero marcar quarta");
            assertThat(linha.get("tipo")).isEqualTo("TEXTO");
            assertThat(linha.get("processada_em")).as("chega sem processar").isNull();
        });
    }

    @Test
    @DisplayName("um POST com dois salões vai para as DUAS caixas, cada uma com a sua")
    void lote_multi_tenant() throws SQLException {
        /*
         * A armadilha da rotina. A Meta agrupa por app, não por número: um app que serve dez
         * salões entrega mensagem de vários no mesmo POST. Resolver o tenant uma vez, no começo
         * do lote, grava a mensagem de uma cliente na caixa de outro salão — e ninguém descobre
         * até alguém ler a conversa errada.
         */
        cenario();
        UUID outro = novoSalao();
        TenantContext.executar(outro,
                () -> registrarCanal.executar(NUMERO_B, "5511988880000", true));

        var resumo = entregar("""
                {"entry":[
                  {"changes":[{"field":"messages","value":{
                    "metadata":{"phone_number_id":"%s"},
                    "messages":[{"from":"5511900000001","id":"wamid.A","timestamp":"1757000000",
                                 "type":"text","text":{"body":"sou do salão A"}}]}}]},
                  {"changes":[{"field":"messages","value":{
                    "metadata":{"phone_number_id":"%s"},
                    "messages":[{"from":"5511900000002","id":"wamid.B","timestamp":"1757000001",
                                 "type":"text","text":{"body":"sou do salão B"}}]}}]}]}
                """.formatted(NUMERO_A, NUMERO_B));

        assertThat(resumo.gravadas()).isEqualTo(2);
        assertThat(caixaDe(salao)).singleElement()
                .extracting(l -> l.get("texto")).isEqualTo("sou do salão A");
        assertThat(caixaDe(outro)).singleElement()
                .extracting(l -> l.get("texto")).isEqualTo("sou do salão B");
    }

    @Test
    @DisplayName("número não cadastrado é descartado e CONTADO, nunca em silêncio")
    void numero_sem_dono() throws SQLException {
        // Silêncio aqui é o pior modo de falha da integração: o número está apontado para o app,
        // não está cadastrado aqui, e toda mensagem se perde sem erro nenhum.
        cenario();

        var resumo = entregar(texto("NUMERO_QUE_NINGUEM_CADASTROU", "wamid.X", "oi"));

        assertThat(resumo.semDono()).isEqualTo(1);
        assertThat(resumo.gravadas()).isZero();
        assertThat(caixaDe(salao)).isEmpty();
    }

    @Test
    @DisplayName("canal desligado não recebe: desativar é diferente de apagar")
    void canal_inativo() throws SQLException {
        cenario();
        TenantContext.executar(salao,
                () -> registrarCanal.executar(NUMERO_A, "5511999990000", false));

        var resumo = entregar(texto(NUMERO_A, "wamid.1", "oi"));

        assertThat(resumo.semDono()).isEqualTo(1);
        assertThat(caixaDe(salao)).isEmpty();
        // O cadastro continua lá, e é isso que permite religar sem redigitar nada.
        assertThat(TenantContext.obter(salao, () -> consultarCanal.executar()))
                .extracting(CanalDoWhatsApp::ativo).isEqualTo(false);
    }

    // ------------------------------------------------------------------ idempotência

    @Test
    @DisplayName("o reenvio da Meta não duplica: o mesmo wamid entra uma vez")
    void reenvio_nao_duplica() throws SQLException {
        // A Meta reenvia quando não recebe 200 a tempo. Sem isto, um "marca quarta 10h" vira
        // dois agendamentos — o modo de falha que RT-INF-005 descreve na sua seção 2.
        cenario();
        String lote = texto(NUMERO_A, "wamid.MESMO", "marca quarta 10h");

        var primeira = entregar(lote);
        var segunda = entregar(lote);

        assertThat(primeira.gravadas()).isEqualTo(1);
        assertThat(segunda.gravadas()).isZero();
        assertThat(segunda.repetidas()).isEqualTo(1);
        assertThat(caixaDe(salao)).hasSize(1);
    }

    @Test
    @DisplayName("reenvio com mensagem NOVA junto entrega só a nova")
    void reenvio_com_mensagem_nova() throws SQLException {
        /*
         * É por isso que a idempotência é por MENSAGEM e não por requisição.
         *
         * A `Idempotencia` de RT-INF-005 é chaveada por um header que o cliente manda, uma por
         * operação. A Meta não manda header nenhum e agrupa: o reenvio traz a mensagem antiga
         * JUNTO com as que chegaram depois. Idempotência por requisição descartaria as novas.
         */
        cenario();
        entregar(texto(NUMERO_A, "wamid.1", "primeira"));

        var resumo = entregar("""
                {"entry":[{"changes":[{"field":"messages","value":{
                  "metadata":{"phone_number_id":"%s"},
                  "messages":[
                    {"from":"5511988887777","id":"wamid.1","timestamp":"1757000000",
                     "type":"text","text":{"body":"primeira"}},
                    {"from":"5511988887777","id":"wamid.2","timestamp":"1757000010",
                     "type":"text","text":{"body":"segunda"}}]}}]}]}
                """.formatted(NUMERO_A));

        assertThat(resumo.gravadas()).isEqualTo(1);
        assertThat(resumo.repetidas()).isEqualTo(1);
        assertThat(caixaDe(salao)).extracting(l -> l.get("texto"))
                .containsExactly("primeira", "segunda");
    }

    @Test
    @DisplayName("o mesmo wamid em salões diferentes não colide")
    void mesmo_wamid_em_dois_saloes() throws SQLException {
        // O wamid é único na Meta, então isto não acontece na prática. O índice inclui o
        // estabelecimento porque a política de RLS precisa da coluna — e porque uma
        // reconfiguração que aponte dois salões para o mesmo número não deve virar erro 500.
        cenario();
        UUID outro = novoSalao();
        TenantContext.executar(outro,
                () -> registrarCanal.executar(NUMERO_B, "5511988880000", true));

        entregar(texto(NUMERO_A, "wamid.IGUAL", "para o A"));
        var resumo = entregar(texto(NUMERO_B, "wamid.IGUAL", "para o B"));

        assertThat(resumo.gravadas()).isEqualTo(1);
        assertThat(caixaDe(salao)).hasSize(1);
        assertThat(caixaDe(outro)).hasSize(1);
    }

    @Test
    @DisplayName("o evento sai UMA vez; o reenvio da Meta não publica de novo")
    void evento_sai_uma_vez() {
        /*
         * A anti-duplicação onde ela importa de verdade: não basta a linha não duplicar — o
         * EVENTO não pode sair duas vezes. É ele que vai disparar o agente (RT-CNV-004), e dois
         * eventos são dois "marca quarta 10h".
         *
         * O que este teste NÃO prova, e é bom saber: que o evento está no outbox. O Spring
         * Modulith grava uma publicação por LISTENER, e `MensagemDeClienteRecebida` ainda não tem
         * nenhum — `event_publication` fica vazia hoje. A garantia de entrega passa a valer em
         * RT-CNV-002, quando o consumidor existir; até então o evento é só publicação em
         * processo. Está registrado nas pendências da rotina.
         */
        cenario();
        String lote = texto(NUMERO_A, "wamid.EVT", "marca quarta 10h");

        entregar(lote);
        entregar(lote);

        assertThat(eventos.stream(MensagemDeClienteRecebida.class))
                .as("o reenvio não publica evento novo")
                .singleElement()
                .satisfies(evento -> {
                    assertThat(evento.estabelecimentoId()).isEqualTo(salao);
                    assertThat(evento.tipo()).isEqualTo(TipoDeMensagem.TEXTO);
                });
    }

    @Test
    @DisplayName("o evento carrega só identificadores — RN-INF-009")
    void evento_sem_pii() {
        /*
         * O payload do evento vive em `event_publication`, que não tem política de tenant: os
         * eventos de todos os estabelecimentos convivem na mesma tabela. Telefone ou texto ali
         * seria dado pessoal fora do perímetro que a RLS garante.
         *
         * O teste é sobre a FORMA do record, e por isso continua valendo quando alguém
         * acrescentar um campo "só para facilitar".
         */
        cenario();
        entregar(texto(NUMERO_A, "wamid.1", "meu telefone novo é 11988887777"));

        var evento = eventos.stream(MensagemDeClienteRecebida.class).findFirst().orElseThrow();

        assertThat(MensagemDeClienteRecebida.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactlyInAnyOrder("estabelecimentoId", "mensagemId", "tipo");
        assertThat(evento.toString())
                .doesNotContain("5511988887777")
                .doesNotContain("telefone novo");
    }

    // ------------------------------------------------------------------ tipos

    @Test
    @DisplayName("áudio guarda o bilhete da mídia, sem texto")
    void audio() throws SQLException {
        cenario();

        entregar("""
                {"entry":[{"changes":[{"field":"messages","value":{
                  "metadata":{"phone_number_id":"%s"},
                  "messages":[{"from":"5511988887777","id":"wamid.AUD","timestamp":"1757000000",
                               "type":"audio","audio":{"id":"MIDIA1",
                                                       "mime_type":"audio/ogg; codecs=opus"}}]}}]}]}
                """.formatted(NUMERO_A));

        assertThat(caixaDe(salao)).singleElement().satisfies(linha -> {
            assertThat(linha.get("tipo")).isEqualTo("AUDIO");
            assertThat(linha.get("midia_id")).isEqualTo("MIDIA1");
            assertThat(linha.get("texto")).isNull();
        });
    }

    @Test
    @DisplayName("tipo não tratado é guardado sem conteúdo")
    void tipo_nao_suportado() throws SQLException {
        cenario();

        entregar("""
                {"entry":[{"changes":[{"field":"messages","value":{
                  "metadata":{"phone_number_id":"%s"},
                  "messages":[{"from":"5511988887777","id":"wamid.R","timestamp":"1757000000",
                               "type":"reaction","reaction":{"emoji":"👍"}}]}}]}]}
                """.formatted(NUMERO_A));

        assertThat(caixaDe(salao)).singleElement().satisfies(linha -> {
            assertThat(linha.get("tipo")).isEqualTo(TipoDeMensagem.NAO_SUPORTADO.name());
            assertThat(linha.get("texto")).isNull();
            assertThat(linha.get("midia_id")).isNull();
        });
    }

    @Test
    @DisplayName("recibo de entrega não entra na caixa")
    void recibo_de_status() throws SQLException {
        cenario();

        var resumo = entregar("""
                {"entry":[{"changes":[{"field":"messages","value":{
                  "metadata":{"phone_number_id":"%s"},
                  "statuses":[{"id":"wamid.OUT","status":"read","timestamp":"1757000000"}]}}]}]}
                """.formatted(NUMERO_A));

        assertThat(resumo.vazio()).isTrue();
        assertThat(caixaDe(salao)).isEmpty();
    }

    // ------------------------------------------------------------------ cadastro do canal

    @Test
    @DisplayName("o número de outro salão é recusado: roteamento é tudo o que o webhook tem")
    void numero_ja_em_uso() throws SQLException {
        cenario();
        UUID outro = novoSalao();

        Throwable erro = catchThrowable(() -> TenantContext.executar(outro,
                () -> registrarCanal.executar(NUMERO_A, "5511900000000", true)));

        assertThat(erro).isInstanceOf(ErroDeDominio.class);
        assertThat(((ErroDeDominio) erro).codigo().codigo())
                .isEqualTo("ER-CNV-NUMERO_JA_EM_USO");
        // E a mensagem continua caindo no salão certo.
        entregar(texto(NUMERO_A, "wamid.1", "oi"));
        assertThat(caixaDe(salao)).hasSize(1);
        assertThat(caixaDe(outro)).isEmpty();
    }

    @Test
    @DisplayName("registrar de novo atualiza, e não cria um segundo canal")
    void registrar_e_idempotente() {
        cenario();

        TenantContext.executar(salao,
                () -> registrarCanal.executar(NUMERO_A, "5511911111111", true));

        assertThat(TenantContext.obter(salao, () -> consultarCanal.executar()))
                .isEqualTo(new CanalDoWhatsApp(NUMERO_A, "5511911111111", true));
    }

    @Test
    @DisplayName("phoneNumberId com espaço é recusado")
    void numero_com_espaco() {
        // Espaço é sempre cola torta, e o efeito seria um cadastro que nunca casa com webhook
        // nenhum — indistinguível a olho do certo. Mesmo raciocínio de RN-EST-026.
        cenario();

        Throwable erro = catchThrowable(() -> TenantContext.executar(salao,
                () -> registrarCanal.executar("111 222", "5511900000000", true)));

        assertThat(((ErroDeDominio) erro).codigo().codigo())
                .isEqualTo("ER-CNV-DADOS_INVALIDOS");
    }

    @Test
    @DisplayName("número formatado é recusado: o banco guarda E.164, um formato só")
    void numero_formatado() {
        /*
         * Aceitar "+55 11 99999-0000" pareceria gentileza e criaria dois formatos do mesmo dado
         * no banco. `cliente.telefone` é E.164 puro e a própria Meta manda
         * `display_phone_number` assim; quem formata é a tela, que já tem componente para isso.
         *
         * Este teste nasceu de um erro meu: a validação recusou a fixture "+55 11 99999-0000" e a
         * saída fácil seria afrouxar a validação. A correção foi a fixture.
         */
        cenario();

        Throwable erro = catchThrowable(() -> TenantContext.executar(salao,
                () -> registrarCanal.executar(NUMERO_B, "+55 11 99999-0000", true)));

        assertThat(((ErroDeDominio) erro).codigo().codigo())
                .isEqualTo("ER-CNV-DADOS_INVALIDOS");
        assertThat(erro).hasMessageContaining("E.164");
    }

    @Test
    @DisplayName("salão sem canal recebe 404, não um nulo")
    void sem_canal() {
        UUID semCanal = novoSalao();

        Throwable erro = catchThrowable(
                () -> TenantContext.executar(semCanal, () -> consultarCanal.executar()));

        assertThat(((ErroDeDominio) erro).codigo().codigo())
                .isEqualTo("ER-CNV-CANAL_NAO_CONFIGURADO");
    }

    @Test
    @WithMockUser(authorities = { Permissao.USUARIO_GERENCIAR })
    @DisplayName("sem config:manage não se cadastra canal")
    void sem_permissao() {
        UUID tenant = novoSalao();

        assertThatThrownBy(() -> TenantContext.executar(tenant,
                () -> registrarCanal.executar(NUMERO_A, "5511900000000", true)))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ------------------------------------------------------------------ o banco garante

    @Test
    @DisplayName("a caixa de entrada é isolada por salão")
    void isolamento() throws SQLException {
        cenario();
        UUID outro = novoSalao();
        entregar(texto(NUMERO_A, "wamid.1", "conversa do salão A"));

        assertThat(caixaDe(outro)).as("o outro salão não vê a conversa alheia").isEmpty();
    }

    @Test
    @DisplayName("a aplicação só pode marcar como processada: o pedido da cliente é prova")
    void grant_de_coluna() throws SQLException {
        /*
         * O consumidor desta tabela vai ser um LLM (RT-CNV-004), e o padrão inegociável do plano
         * é que ele nunca escreva direto no domínio. Aqui a garantia não é o prompt: é a
         * permissão de coluna. Reescrever `texto` seria reescrever o que a cliente pediu.
         *
         * Mesmo padrão de `fechamento.estornado_em` (V43).
         */
        cenario();
        entregar(texto(NUMERO_A, "wamid.1", "quero marcar quarta"));

        var conexao = comoApp(salao);
        // O que a aplicação PODE.
        try (var ps = conexao.prepareStatement(
                "update mensagem_recebida set processada_em = now()")) {
            assertThat(ps.executeUpdate()).as("marcar como processada").isOne();
        }
        conexao.rollback();

        // O que ela NÃO pode.
        for (String proibido : List.of("update mensagem_recebida set texto = 'outra coisa'",
                                       "update mensagem_recebida set de_telefone = '5500000000000'",
                                       "delete from mensagem_recebida")) {
            try (var ps = conexao.prepareStatement(proibido)) {
                Throwable erro = catchThrowable(ps::executeUpdate);
                assertThat(erro).as("`%s` deveria ser recusado", proibido).isNotNull();
                assertThat(erro.getMessage()).containsIgnoringCase("permission denied");
            }
            conexao.rollback();
        }
    }

    @Test
    @DisplayName("apagar canal é proibido: some o cadastro, somem as mensagens em silêncio")
    void canal_nao_se_apaga() throws SQLException {
        cenario();

        var conexao = comoApp(salao);
        try (var ps = conexao.prepareStatement("delete from canal_whatsapp")) {
            Throwable erro = catchThrowable(ps::executeUpdate);
            assertThat(erro).isNotNull();
            assertThat(erro.getMessage()).containsIgnoringCase("permission denied");
        }
        conexao.rollback();
    }
}
