package br.com.salao.financeiro.internal.infra;

import br.com.salao.financeiro.api.FormaDoVale;
import br.com.salao.financeiro.api.Vale;
import br.com.salao.shared.dinheiro.Money;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-005 — os vales.
 *
 * <p>Só {@code insert} e {@code select}, como o razão: a role da aplicação não tem {@code update}
 * nem {@code delete} nesta tabela (V41). Corrigir um vale é cancelá-lo e registrar outro.
 *
 * <p>{@code cancelado} é <strong>calculado</strong> a partir do razão, não lido de coluna. Uma
 * coluna de status seria uma segunda verdade sobre o mesmo fato, e as duas divergiriam no primeiro
 * caminho que esquecesse de escrever numa delas.
 */
public class ValesJdbc {

    private static final String CAMPOS = """
            v.id, v.profissional_id, v.valor, v.forma, v.data_competencia, v.motivo, v.criado_em,
            exists (select 1 from lancamento e
                     where e.origem_tipo = 'VALE' and e.origem_id = v.id
                       and e.tipo = 'ESTORNO') as cancelado
            """;

    private final JdbcClient jdbc;

    public ValesJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public UUID inserir(UUID estabelecimentoId, UUID profissionalId, Money valor,
                        FormaDoVale forma, LocalDate competencia, String motivo,
                        Instant criadoEm, UUID criadoPor) {
        return jdbc.sql("""
                insert into vale (estabelecimento_id, profissional_id, valor, forma,
                                  data_competencia, motivo, criado_em, criado_por)
                values (:estabelecimento, :profissional, :valor, :forma, :competencia, :motivo,
                        :criadoEm, :por)
                returning id
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("profissional", profissionalId)
                .param("valor", valor.valor())
                .param("forma", forma.name())
                .param("competencia", Date.valueOf(competencia))
                .param("motivo", motivo)
                .param("criadoEm", OffsetDateTime.ofInstant(criadoEm, ZoneOffset.UTC))
                .param("por", criadoPor)
                .query(UUID.class)
                .single();
    }

    @Transactional(readOnly = true)
    public Optional<Vale> porId(UUID id) {
        return jdbc.sql("select " + CAMPOS + " from vale v where v.id = :id")
                .param("id", id)
                .query(ValesJdbc::mapear)
                .optional();
    }

    /**
     * Os vales de um período, de um profissional ou de todos.
     *
     * <p>O filtro anulável é um {@code is null or} no próprio SQL, e não duas consultas: duas
     * divergiriam na primeira coluna nova, e a segunda a divergir seria a que ninguém abre.
     *
     * <p>O {@code cast} explícito existe porque um parâmetro nulo chega ao PostgreSQL sem tipo, e
     * {@code null is null} sem tipo é ambíguo — o banco recusa antes de comparar coisa alguma.
     */
    @Transactional(readOnly = true)
    public List<Vale> doPeriodo(UUID profissionalId, LocalDate de, LocalDate ate) {
        return jdbc.sql("select " + CAMPOS + """
                  from vale v
                 where v.data_competencia between :de and :ate
                   and (cast(:profissional as uuid) is null
                        or v.profissional_id = :profissional)
                 order by v.data_competencia, v.criado_em, v.id
                """)
                .param("profissional", profissionalId)
                .param("de", Date.valueOf(de))
                .param("ate", Date.valueOf(ate))
                .query(ValesJdbc::mapear)
                .list();
    }

    private static Vale mapear(ResultSet rs, int linha) throws SQLException {
        return new Vale(
                rs.getObject("id", UUID.class),
                rs.getObject("profissional_id", UUID.class),
                new Money(rs.getBigDecimal("valor")),
                FormaDoVale.valueOf(rs.getString("forma")),
                rs.getDate("data_competencia").toLocalDate(),
                rs.getString("motivo"),
                rs.getBoolean("cancelado"),
                rs.getObject("criado_em", OffsetDateTime.class).toInstant());
    }
}
