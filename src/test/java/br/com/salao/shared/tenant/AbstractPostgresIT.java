package br.com.salao.shared.tenant;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base dos testes de integração: Postgres <strong>real</strong>, nunca H2.
 *
 * <p>H2 não tem {@code EXCLUDE}, nem RLS, nem {@code tstzrange} — testar contra ele seria testar
 * outro sistema, e justamente as três coisas de que este projeto mais depende.
 *
 * <p>Reproduz a topologia de produção: o Flyway conecta como <em>owner</em> (precisa criar
 * tabela, role e política) e a aplicação como {@code salao_app}, que não é dona de nada. Se os
 * dois usassem a mesma role, a RLS seria ignorada em silêncio e todo teste de isolamento passaria
 * sem provar coisa alguma.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
abstract class AbstractPostgresIT {

    static final String OWNER = "salao_owner";
    static final String OWNER_SENHA = "salao_owner_dev";
    static final String APP_SENHA = "salao_app_dev";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withDatabaseName("salao")
            .withUsername(OWNER)
            .withPassword(OWNER_SENHA);

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registro) {
        registro.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registro.add("spring.flyway.user", () -> OWNER);
        registro.add("spring.flyway.password", () -> OWNER_SENHA);
        registro.add("spring.flyway.placeholders.senha_app", () -> APP_SENHA);

        registro.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registro.add("spring.datasource.username", () -> "salao_app");
        registro.add("spring.datasource.password", () -> APP_SENHA);

        // Pool de 1 de propósito: força o reuso de conexão entre requisições de tenants
        // diferentes, que é a única forma de provocar o vazamento por SET sem LOCAL.
        registro.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
    }

    /** Conexão de owner: ignora a RLS. Só para preparar cenário, nunca para asserção. */
    protected Connection comoOwner() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), OWNER, OWNER_SENHA);
    }

    @BeforeEach
    void limparDados() throws SQLException {
        try (var conexao = comoOwner(); var st = conexao.createStatement()) {
            st.execute("truncate auditoria, estabelecimento restart identity cascade");
        }
    }

    protected UUID criarEstabelecimento(String nome) throws SQLException {
        try (var conexao = comoOwner();
             var ps = conexao.prepareStatement(
                     "insert into estabelecimento (nome) values (?) returning id")) {
            ps.setString(1, nome);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    protected void criarAuditorias(UUID estabelecimentoId, int quantidade) throws SQLException {
        try (var conexao = comoOwner();
             var ps = conexao.prepareStatement("""
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
