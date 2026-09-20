package br.com.salao.financeiro.internal.infra;

import br.com.salao.financeiro.api.FormaDePagamento;
import br.com.salao.financeiro.api.PagamentoResumo;
import br.com.salao.financeiro.api.StatusDeConciliacao;
import br.com.salao.financeiro.internal.domain.Recebimento;
import br.com.salao.shared.dinheiro.Money;
import java.math.BigDecimal;
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
                        String autorizacao, StatusDeConciliacao conciliacao,
                        Recebimento.Previsao previsao, Instant criadoEm, UUID criadoPor) {
        return jdbc.sql("""
                insert into pagamento (estabelecimento_id, comanda_id, forma, valor, bandeira,
                                       parcelas, nsu, autorizacao, status_conciliacao,
                                       taxa_prevista, previsao_recebimento, dias_para_receber,
                                       criado_em, criado_por)
                values (:estabelecimento, :comanda, :forma, :valor, :bandeira, :parcelas, :nsu,
                        :autorizacao, :conciliacao, :taxa, :previsao, :dias, :criadoEm, :por)
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
                // RT-FIN-012: nulos quando a faixa não está cadastrada — previsão chutada vira
                // número na tela do dono, e número na tela do dono vira decisão (RN-FIN-037).
                .param("taxa", previsao == null ? null : previsao.taxaTotal().valor())
                .param("previsao", previsao == null ? null
                        : Date.valueOf(previsao.primeiraParcela()))
                .param("dias", previsao == null ? null : previsao.diasParaReceber())
                .param("criadoEm", OffsetDateTime.ofInstant(criadoEm, ZoneOffset.UTC))
                .param("por", criadoPor)
                .query(UUID.class)
                .single();
    }

    // ------------------------------------------------------------------ RT-FIN-012

    /** Um pagamento no cartão com previsão fotografada, pronto para virar cronograma. */
    public record ComPrevisao(UUID id, Money valor, int parcelas, Money taxaPrevista,
                              LocalDate primeiraParcela, int diasParaReceber) {
    }

    /**
     * Os pagamentos cujas parcelas podem cair na janela pedida.
     *
     * <p>Superset de propósito: quem recorta parcela a parcela é o domínio, que conhece o calendário
     * bancário. O SQL só garante que nada relevante fique de fora — daí olhar {@value #DIAS_DE_FOLGA}
     * dias para trás da primeira parcela, que cobre doze parcelas de 30 dias com sobra para feriado.
     */
    @Transactional(readOnly = true)
    public List<ComPrevisao> comPrevisaoAte(LocalDate de, LocalDate ate) {
        return jdbc.sql("""
                select id, valor, parcelas, taxa_prevista, previsao_recebimento, dias_para_receber
                  from pagamento
                 where forma in ('CREDITO', 'DEBITO') and previsao_recebimento is not null
                   and dias_para_receber is not null
                   and previsao_recebimento <= :ate
                   and previsao_recebimento >= :de
                """)
                .param("ate", Date.valueOf(ate))
                .param("de", Date.valueOf(de.minusDays(DIAS_DE_FOLGA)))
                .query((rs, n) -> new ComPrevisao(rs.getObject("id", UUID.class),
                        new Money(rs.getBigDecimal("valor")), rs.getInt("parcelas"),
                        new Money(rs.getBigDecimal("taxa_prevista")),
                        rs.getDate("previsao_recebimento").toLocalDate(),
                        rs.getInt("dias_para_receber")))
                .list();
    }

    /** Doze parcelas de 30 dias, com folga para o empurrão de feriado. */
    public static final int DIAS_DE_FOLGA = 400;

    /**
     * Vendas recentes no cartão que ficaram sem previsão — faixa sem contrato na hora do pagamento.
     *
     * <p>Recentes, e não todas: os pagamentos anteriores a esta rotina nunca terão previsão, e contá-los
     * deixaria um número grande e imóvel na tela, que ninguém olha duas vezes.
     */
    @Transactional(readOnly = true)
    public int semPrevisaoDesde(Instant desde) {
        return jdbc.sql("""
                select count(*) from pagamento
                 where forma in ('CREDITO', 'DEBITO') and previsao_recebimento is null
                   and criado_em >= :desde
                """)
                .param("desde", OffsetDateTime.ofInstant(desde, ZoneOffset.UTC))
                .query(Integer.class)
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
