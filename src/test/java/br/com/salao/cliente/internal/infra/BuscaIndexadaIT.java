package br.com.salao.cliente.internal.infra;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.shared.tenant.AbstractPostgresIT;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RT-CLI-002 — as consultas de busca realmente usam os índices, num salão grande.
 *
 * <h2>Por que isto merece teste próprio, e por que ele custa caro</h2>
 *
 * <p>Os testes de comportamento passam com cinco clientes — e passariam também com os índices
 * ausentes. O dia em que isso doeria é o dia em que o salão tem dezenas de milhares de fichas e a
 * busca começa a demorar, com o telefone tocando, que é exatamente quando ela é usada.
 *
 * <p>Duas versões anteriores deste teste <strong>não provavam nada</strong>, e vale registrar
 * porque o erro é fácil de repetir:
 *
 * <ol>
 *   <li>com a tabela vazia, todo índice custa o mesmo e o planejador escolheu um qualquer,
 *       aplicando o {@code LIKE} como filtro;
 *   <li>com cinco mil linhas, o planejador preferiu varrer um índice parcial inteiro — e estava
 *       <em>certo</em>: nesse tamanho é mesmo mais barato.
 * </ol>
 *
 * <p>Por isso {@link #FICHAS} é grande. O número não é decorativo: é onde o plano vira, e é a
 * resposta honesta para "a partir de quando o índice paga o próprio custo".
 */
class BuscaIndexadaIT extends AbstractPostgresIT {

    /** Onde o planejador passa a preferir o índice à varredura. Medido, não estimado. */
    private static final int FICHAS = 100_000;

    @Test
    @DisplayName("num salão grande, os dois caminhos de busca usam índice")
    void os_dois_caminhos_usam_indice() throws SQLException {
        povoar();

        // Telefone: sufixo vira prefixo pelo `reverse`, e um btree resolve exatamente.
        // Trigram foi tentado aqui e é a ferramenta errada — trigrama de dígito tem seletividade
        // péssima, porque todo número do salão compartilha os mesmos pedacinhos.
        assertThat(planoDe(ClientesJdbc.BUSCAR_POR_TELEFONE))
                .contains("idx_cliente_telefone_reverso");

        // Nome: substring em texto é o caso para o qual o trigram existe.
        assertThat(planoDe(ClientesJdbc.BUSCAR_POR_NOME)).contains("idx_cliente_busca");
    }

    @Test
    @DisplayName("a coluna de busca é gerada pelo banco, não preenchida pela aplicação")
    void busca_e_coluna_gerada() throws SQLException {
        // Se alguém a transformar em coluna comum preenchida em Java, a normalização passará a
        // existir em dois lugares — e no dia em que divergirem, a busca para de achar gente sem
        // erro nenhum, que é o pior tipo de defeito.
        try (Statement st = comoOwner().createStatement();
             var rs = st.executeQuery("""
                     select is_generated from information_schema.columns
                      where table_name = 'cliente' and column_name = 'busca'
                     """)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("ALWAYS");
        }
    }

    /**
     * Sem {@code ANALYZE} o planejador decide com estatística de tabela recém-criada, que é quase
     * um chute — foi assim que a primeira versão deste teste passou sem provar nada.
     */
    private void povoar() throws SQLException {
        UUID tenant = criarEstabelecimento("Salão grande");
        try (var ps = comoOwner().prepareStatement("""
                insert into cliente (estabelecimento_id, nome, telefone)
                select ?, 'Cliente ' || g, '5511' || lpad(g::text, 9, '0')
                  from generate_series(1, ?) g
                """)) {
            ps.setObject(1, tenant);
            ps.setInt(2, FICHAS);
            ps.executeUpdate();
        }
        try (Statement st = comoOwner().createStatement()) {
            st.execute("analyze cliente");
        }
    }

    /**
     * A conexão de owner é compartilhada pela suíte e <strong>não</strong> se fecha aqui: fechá-la
     * devolveria a rotatividade de conexões que a classe-base evita de propósito.
     */
    /**
     * Troca os parâmetros nomeados por literais para poder dar {@code EXPLAIN}.
     *
     * <p>Existe para que o teste meça a consulta <strong>real</strong>, lida da constante, e não
     * uma cópia. A primeira versão tinha o SQL transcrito à mão e já havia divergido do produto
     * numa migração — medindo com precisão um plano que ninguém executava.
     */
    private static String comLiterais(String sql) {
        return sql.replace(":termo", "'silva'")
                .replace(":padrao", "'silva'")
                .replace(":digitos", "'4321'")
                .replace(":limite", "20");
    }

    private String planoDe(String sql) throws SQLException {
        try (Statement st = comoOwner().createStatement();
             var rs = st.executeQuery("explain " + comLiterais(sql))) {
            var plano = new StringBuilder();
            while (rs.next()) {
                plano.append(rs.getString(1)).append('\n');
            }
            return plano.toString();
        }
    }
}
