package br.com.salao.shared.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Leitura de coluna anulável sem a armadilha do {@code wasNull}.
 *
 * <h2>Por que isto existe</h2>
 *
 * <p>{@code rs.getInt} devolve {@code 0} para {@code NULL}, e {@code rs.wasNull()} diz se foi isso
 * que aconteceu — <strong>referente à última coluna lida</strong>. O par funciona quando as duas
 * chamadas estão coladas, e quebra em silêncio quando não estão:
 *
 * <pre>{@code
 * // ERRADO: os argumentos são avaliados da esquerda para a direita, então getString roda
 * // ANTES de wasNull — que passa a falar da coluna errada.
 * new Linha(rs.getObject("id", UUID.class), rs.wasNull() ? null : rs.getInt("duracao"));
 * }</pre>
 *
 * <p>O sintoma é o pior possível: todo valor nulo vira zero, e zero costuma ser um valor válido.
 * Nada estoura.
 *
 * <p>Aconteceu duas vezes neste projeto — RT-CAT-004 e RT-ARQ-001 —, a segunda com o comentário
 * explicando o problema escrito quinze arquivos ao lado. Um comentário não impede o erro; uma
 * função que não tem como ser usada errado, sim.
 */
public final class Colunas {

    private Colunas() {
    }

    /** O inteiro da coluna, ou {@code null} se ela for {@code NULL}. */
    public static Integer inteiroOuNulo(ResultSet rs, String coluna) throws SQLException {
        int valor = rs.getInt(coluna);
        return rs.wasNull() ? null : valor;
    }

    /** O longo da coluna, ou {@code null} se ela for {@code NULL}. */
    public static Long longoOuNulo(ResultSet rs, String coluna) throws SQLException {
        long valor = rs.getLong(coluna);
        return rs.wasNull() ? null : valor;
    }
}
