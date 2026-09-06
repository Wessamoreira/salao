package br.com.salao.financeiro.internal.infra;

import br.com.salao.financeiro.api.FormaDePagamento;
import br.com.salao.financeiro.api.PagamentoResumo;
import br.com.salao.financeiro.api.StatusDeConciliacao;
import br.com.salao.shared.dinheiro.Money;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** RT-FIN-001 — os pagamentos. */
public class PagamentosJdbc {

    private static final String CAMPOS = """
            id, comanda_id, forma, valor, bandeira, parcelas, nsu, autorizacao,
            taxa_prevista, previsao_recebimento, status_conciliacao, criado_em
            """;

    private final JdbcClient jdbc;

    public PagamentosJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public UUID inserir(UUID estabelecimentoId, UUID comandaId, FormaDePagamento forma,
                        Money valor, String bandeira, int parcelas, String nsu,
                        String autorizacao, StatusDeConciliacao conciliacao, Instant criadoEm,
                        UUID criadoPor) {
        return jdbc.sql("""
                insert into pagamento (estabelecimento_id, comanda_id, forma, valor, bandeira,
                                       parcelas, nsu, autorizacao, status_conciliacao,
                                       criado_em, criado_por)
                values (:estabelecimento, :comanda, :forma, :valor, :bandeira, :parcelas, :nsu,
                        :autorizacao, :conciliacao, :criadoEm, :por)
                returning id
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("comanda", comandaId)
                .param("forma", forma.name())
                .param("valor", valor.valor())
                .param("bandeira", bandeira)
                .param("parcelas", parcelas)
                .param("nsu", nsu)
                .param("autorizacao", autorizacao)
                .param("conciliacao", conciliacao.name())
                .param("criadoEm", OffsetDateTime.ofInstant(criadoEm, ZoneOffset.UTC))
                .param("por", criadoPor)
                .query(UUID.class)
                .single();
    }

    @Transactional(readOnly = true)
    public Optional<PagamentoResumo> porId(UUID id) {
        return jdbc.sql("select " + CAMPOS + " from pagamento where id = :id")
                .param("id", id)
                .query(PagamentosJdbc::mapear)
                .optional();
    }

    /** Na ordem em que foram registrados: é assim que a tela de fechamento os mostra. */
    @Transactional(readOnly = true)
    public List<PagamentoResumo> daOrigem(UUID comandaId) {
        return jdbc.sql("select " + CAMPOS + """
                  from pagamento where comanda_id = :comanda order by criado_em, id
                """)
                .param("comanda", comandaId)
                .query(PagamentosJdbc::mapear)
                .list();
    }

    /**
     * A soma, no banco.
     *
     * <p>{@code coalesce} porque {@code sum} de conjunto vazio é nulo, e "nada foi pago" é zero —
     * devolver nulo aqui obrigaria todo chamador a um {@code if} que só existe por causa do SQL.
     */
    @Transactional(readOnly = true)
    public Money totalPago(UUID comandaId) {
        BigDecimal total = jdbc.sql("""
                select coalesce(sum(valor), 0) from pagamento where comanda_id = :comanda
                """)
                .param("comanda", comandaId)
                .query(BigDecimal.class)
                .single();
        return new Money(total);
    }

    @Transactional
    public int remover(UUID id) {
        return jdbc.sql("delete from pagamento where id = :id").param("id", id).update();
    }

    private static PagamentoResumo mapear(ResultSet rs, int linha) throws SQLException {
        BigDecimal taxa = rs.getBigDecimal("taxa_prevista");
        Date previsao = rs.getDate("previsao_recebimento");
        return new PagamentoResumo(
                rs.getObject("id", UUID.class),
                rs.getObject("comanda_id", UUID.class),
                FormaDePagamento.valueOf(rs.getString("forma")),
                new Money(rs.getBigDecimal("valor")),
                rs.getString("bandeira"),
                rs.getInt("parcelas"),
                rs.getString("nsu"),
                rs.getString("autorizacao"),
                taxa == null ? null : new Money(taxa),
                previsao == null ? null : previsao.toLocalDate(),
                StatusDeConciliacao.valueOf(rs.getString("status_conciliacao")),
                rs.getObject("criado_em", OffsetDateTime.class).toInstant());
    }
}
