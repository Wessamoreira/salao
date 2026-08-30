package br.com.salao.shared.tenant;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base dos testes de integração: Postgres <strong>real</strong>, nunca H2.
 *
 * <p>H2 não tem {@code EXCLUDE}, nem RLS, nem {@code tstzrange} — testar contra ele seria testar
 * outro sistema, e justamente as três coisas de que este projeto mais depende.
 *
 * <p>Reproduz a topologia de produção: o Flyway conecta como <em>owner</em> (precisa criar tabela,
 * role e política) e a aplicação como {@code salao_app}, que não é dona de nada. Se os dois
 * usassem a mesma role, o Postgres ignoraria a RLS e todo teste de isolamento passaria sem provar
 * nada.
 *
 * <p>O endereço do Docker vem do profile Maven {@code colima}, que se ativa sozinho quando o
 * socket do Colima existe. Tentar configurar isso por {@code System.setProperty("docker.host")}
 * <em>não funciona</em>: o Testcontainers lê apenas variável de ambiente e o arquivo
 * {@code ~/.testcontainers.properties}, nunca propriedade de sistema.
 *
 * <p><strong>Container singleton, não {@code @Container}.</strong> Com {@code @Testcontainers} +
 * {@code @Container}, a extensão para o container no {@code afterAll} de cada classe e cria um
 * novo na seguinte — enquanto o contexto do Spring segue cacheado apontando para o anterior. O
 * sintoma é obscuro: a segunda classe de teste falha com "relation does not exist" num banco
 * recém-criado. Iniciado uma vez aqui, o container vive pelo JVM inteiro e o Ryuk o remove no fim.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractPostgresIT {

    public static final String OWNER = "salao_owner";
    static final String OWNER_SENHA = "salao_owner_dev";
    static final String APP_SENHA = "salao_app_dev";
    static final String MANUTENCAO_SENHA = "salao_manutencao_dev";

    static final PostgreSQLContainer POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer("postgres:18")
                .withDatabaseName("salao")
                .withUsername(OWNER)
                .withPassword(OWNER_SENHA)
                // O padrão de 100 não basta: o Spring cria UM CONTEXTO POR CONJUNTO
                // DISTINTO DE PROPRIEDADES, e cada contexto guarda os próprios pools
                // (aplicação + manutenção) mais a conexão de LISTEN do cache. Com a suíte
                // crescendo, o total passou de 100 e as classes começaram a falhar em
                // conjunto enquanto passavam isoladas — sintoma que não aponta para a causa.
                .withCommand("postgres", "-c", "max_connections=300");
        POSTGRES.start();
        definirSenhasDasRoles();
    }

    /**
     * Faz aqui o que o operador faz uma vez por ambiente (RT-INF-012).
     *
     * <p>As migrations criam as roles SEM senha de propósito: `alter role ... password 'x'`
     * gravaria o segredo dentro do comando SQL, e com `log_statement` ligado ele iria para o log
     * do servidor em texto claro. A senha é definida fora da migration — em produção pelo
     * runbook, aqui pelo teste, sempre pela conexão de owner.
     *
     * <p>Precisa rodar antes de o Spring subir: o Flyway conecta como owner, mas a aplicação
     * conecta como `salao_app`, e sem senha ela não autenticaria.
     */
    private static void definirSenhasDasRoles() {
        try (var conexao = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), OWNER, OWNER_SENHA);
             var st = conexao.createStatement()) {
            // A role só existe depois da migration V2/V4, que ainda não rodou. Criar aqui é o
            // mesmo que o provisionamento faz: garantir que exista e ter senha.
            st.execute("do $$ begin"
                    + " if not exists (select 1 from pg_roles where rolname = 'salao_app')"
                    + " then create role salao_app login; end if;"
                    + " if not exists (select 1 from pg_roles where rolname = 'salao_manutencao')"
                    + " then create role salao_manutencao login; end if;"
                    + " end $$;");
            st.execute("alter role salao_app password '" + APP_SENHA + "'");
            st.execute("alter role salao_manutencao password '" + MANUTENCAO_SENHA + "'");
        } catch (SQLException e) {
            throw new IllegalStateException("não foi possível preparar as roles do teste", e);
        }
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registro) {
        registro.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registro.add("spring.flyway.user", () -> OWNER);
        registro.add("spring.flyway.password", () -> OWNER_SENHA);

        registro.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registro.add("spring.datasource.username", () -> "salao_app");
        registro.add("spring.datasource.password", () -> APP_SENHA);

        registro.add("app.manutencao.url", POSTGRES::getJdbcUrl);
        registro.add("app.manutencao.username", () -> "salao_manutencao");
        registro.add("app.manutencao.password", () -> MANUTENCAO_SENHA);
        registro.add("app.jwt.segredo", () -> "segredo-de-teste-com-mais-de-trinta-e-dois-bytes");
        registro.add("app.cripto.chave",
                () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");

        // Pool pequeno por padrão em teste: nenhum teste precisa de 20 conexões, e o custo
        // de manter pools grandes se multiplica por contexto. Classes que precisem de outro
        // valor declaram o seu (TenantIsolamentoIT usa 1; IdempotenciaIT usa 4).
        registro.add("spring.datasource.hikari.maximum-pool-size", () -> "5");
        registro.add("spring.flyway.placeholders.senha_manutencao", () -> MANUTENCAO_SENHA);

    }

    /**
     * Conexão de owner: ignora a RLS. Só para preparar cenário, nunca para asserção.
     *
     * <p><strong>Uma só, reaproveitada.</strong> Abrir uma conexão por chamada de helper significa
     * meia dúzia de handshakes TCP por teste atravessando o port-forward da VM do Colima, e sob
     * essa rotatividade o forward derruba conexão com {@code EOFException} — falha intermitente
     * que parece bug de isolamento e não é. É fechada no fim da suíte.
     */
    protected static Connection comoOwner() throws SQLException {
        if (conexaoDeOwner == null || conexaoDeOwner.isClosed()) {
            conexaoDeOwner = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), OWNER, OWNER_SENHA);
        }
        return conexaoDeOwner;
    }

    private static Connection conexaoDeOwner;

    @AfterAll
    static void fecharConexaoDeOwner() throws SQLException {
        if (conexaoDeOwner != null && !conexaoDeOwner.isClosed()) {
            conexaoDeOwner.close();
            conexaoDeOwner = null;
        }
    }

    @BeforeEach
    void limparDados() throws SQLException {
        try (var st = comoOwner().createStatement()) {
            st.execute("truncate auditoria, refresh_token, mfa_credencial, "
                    + "mfa_codigo_recuperacao, usuario, estabelecimento "
                    + "restart identity cascade");
        }
    }

    protected UUID criarEstabelecimento(String nome) throws SQLException {
        try (var ps = comoOwner().prepareStatement(
                "insert into estabelecimento (nome) values (?) returning id")) {
            ps.setString(1, nome);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    protected void criarAuditorias(UUID estabelecimentoId, int quantidade) throws SQLException {
        try (var ps = comoOwner().prepareStatement("""
                insert into auditoria (estabelecimento_id, ator, acao, entidade)
                values (?, 'SISTEMA', 'TESTE', 'teste')
                """)) {
            for (int i = 0; i < quantidade; i++) {
                ps.setObject(1, estabelecimentoId);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
