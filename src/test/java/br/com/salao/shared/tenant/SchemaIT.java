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
