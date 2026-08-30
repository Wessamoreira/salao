package br.com.salao.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RT-INF-002 — o teste que faz a garantia sobreviver ao tempo.
 *
 * <p>Os testes de {@code TenantIsolamentoIT} provam que o isolamento funciona hoje. Este prova
 * que continuará funcionando: ele varre o schema real e quebra o build no dia em que alguém
 * criar uma migration esquecendo {@code estabelecimento_id}, a RLS ou o {@code FORCE}.
 *
 * <p>Sem ele, o esquecimento é silencioso e só aparece quando existir o segundo cliente.
 */
class SchemaIT extends AbstractPostgresIT {

    /**
     * Tabelas que legitimamente não têm {@code estabelecimento_id}:
     * <ul>
     *   <li>{@code flyway_schema_history} — infraestrutura de migration;
     *   <li>{@code estabelecimento} — é a raiz do tenant; a política dela compara o próprio
     *       {@code id}, e isso é verificado à parte.
     * </ul>
     * Acrescentar nome aqui é decisão consciente, não conveniência para calar o teste.
     */
    private static final Set<String> SEM_COLUNA_DE_TENANT = Set.of("estabelecimento");

    /**
     * Tabelas de infraestrutura, fora da verificação por inteiro. Cada uma precisa de uma razão
     * escrita — a lista existe para tornar a exceção visível, não para calar o teste.
     *
     * <ul>
     *   <li>{@code flyway_schema_history} — controle de migration;
     *   <li>{@code event_publication} / {@code event_publication_archive} — outbox do Spring
     *       Modulith. Estrutura de terceiro, que não conhece nem preencheria uma coluna de tenant;
     *       e o reenvio de pendências precisa atravessar estabelecimentos, o que sob RLS não
     *       seria possível. O preço está em RN-INF-009: evento carrega ID, nunca PII.
     * </ul>
     */
    @Test
    @DisplayName("as roles carregam seus próprios timeouts")
    void roles_tem_timeouts() throws SQLException {
        // Nas ROLES e não no cliente: assim os limites acompanham a credencial, inclusive num
        // psql aberto às pressas durante um incidente — que é quando alguém roda um SELECT sem
        // WHERE numa tabela grande.
        var configuracoes = configuracaoDasRoles();

        assertThat(configuracoes.get("salao_app"))
                .as("sem statement_timeout, uma query ruim segura a conexão indefinidamente")
                .contains("statement_timeout=30s")
                .as("o lock_timeout é o teto que RT-INF-005 assumiu por escrito e não tinha")
                .contains("lock_timeout=5s")
                .contains("idle_in_transaction_session_timeout=60s");

        assertThat(configuracoes.get("salao_manutencao"))
                .as("purga varre tabela inteira e leva mais que 30s legitimamente")
                .contains("statement_timeout=10min");
    }

    @Test
    @DisplayName("nenhuma senha de role foi gravada em migration")
    void migrations_nao_carregam_senha() throws Exception {
        // `alter role ... password 'x'` grava o segredo dentro do comando SQL, e com
        // log_statement ligado ele vai para o log do servidor em texto claro.
        var migrations = java.nio.file.Files.walk(
                java.nio.file.Path.of("src/main/resources/db/migration")).toList();

        for (var arquivo : migrations) {
            if (!arquivo.toString().endsWith(".sql")) continue;
            // Comentários fora: a regra é sobre COMANDO, e o próprio V2 explica a regra em
            // prosa. Um teste que reprova a explicação da regra é um teste ingênuo.
            String comandos = java.nio.file.Files.readString(arquivo).lines()
                    .filter(linha -> !linha.stripLeading().startsWith("--"))
                    .reduce("", (a, b) -> a + "\n" + b);

            assertThat(comandos)
                    .as("%s grava senha dentro do SQL; com log_statement ligado ela vai "
                            + "para o log do servidor em texto claro", arquivo.getFileName())
                    .doesNotContain("password '");
        }
    }

    private java.util.Map<String, String> configuracaoDasRoles() throws SQLException {
        var porRole = new java.util.HashMap<String, String>();
        try (var st = comoOwner().createStatement();
             var rs = st.executeQuery(
                     "select rolname, coalesce(array_to_string(rolconfig, ','), '') as conf"
                     + " from pg_roles where rolname like 'salao_%'")) {
            while (rs.next()) {
                porRole.put(rs.getString("rolname"), rs.getString("conf"));
            }
        }
        return porRole;
    }

    private static final Set<String> TABELAS_DE_INFRAESTRUTURA = Set.of(
            "flyway_schema_history", "event_publication", "event_publication_archive");

    @Test
    @DisplayName("toda tabela de negócio tem estabelecimento_id, RLS e FORCE")
    void toda_tabela_de_negocio_tem_estabelecimento_id_rls_e_force() throws SQLException {
        List<String> problemas = new ArrayList<>();

        try (var conexao = comoOwner();
             var st = conexao.createStatement();
             var rs = st.executeQuery("""
                     select c.relname                as tabela,
                            c.relrowsecurity         as rls,
                            c.relforcerowsecurity    as force_rls,
                            exists (select 1 from information_schema.columns col
                                     where col.table_schema = 'public'
                                       and col.table_name = c.relname
                                       and col.column_name = 'estabelecimento_id') as tem_coluna,
                            exists (select 1 from pg_policies p
                                     where p.schemaname = 'public'
                                       and p.tablename = c.relname) as tem_politica
                     from pg_class c
                     join pg_namespace n on n.oid = c.relnamespace
                     where n.nspname = 'public' and c.relkind = 'r'
                     order by c.relname
                     """)) {

            while (rs.next()) {
                String tabela = rs.getString("tabela");
                if (TABELAS_DE_INFRAESTRUTURA.contains(tabela)) {
                    continue;
                }
                if (!SEM_COLUNA_DE_TENANT.contains(tabela) && !rs.getBoolean("tem_coluna")) {
                    problemas.add(tabela + ": sem coluna estabelecimento_id");
                }
                if (!rs.getBoolean("rls")) {
                    problemas.add(tabela + ": RLS não habilitada");
                }
                if (!rs.getBoolean("force_rls")) {
                    problemas.add(tabela + ": sem FORCE ROW LEVEL SECURITY "
                            + "(o dono da tabela ignoraria a política)");
                }
                if (!rs.getBoolean("tem_politica")) {
                    problemas.add(tabela + ": nenhuma policy definida");
                }
            }
        }

        assertThat(problemas)
                .as("migration nova precisa chamar aplicar_rls_tenant('<tabela>')")
                .isEmpty();
    }
}
