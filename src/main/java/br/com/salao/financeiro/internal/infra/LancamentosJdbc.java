package br.com.salao.financeiro.internal.infra;

import br.com.salao.financeiro.api.Lancamento;
import br.com.salao.financeiro.api.TipoDeLancamento;
import br.com.salao.shared.dinheiro.Money;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-003 — o livro-razão.
 *
 * <p>Só {@code insert} e {@code select}. Não há {@code atualizar} nem {@code remover} aqui, e não
 * é disciplina de quem escreve: a role da aplicação não tem essas permissões no banco (V40).
 */
public class LancamentosJdbc {

    private static final String CAMPOS = """
            id, data_competencia, tipo, origem_tipo, origem_id, contraparte_tipo,
            contraparte_id, valor, descricao, estorna_lancamento_id
            """;

    private final JdbcClient jdbc;

    public LancamentosJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public UUID inserir(UUID estabelecimentoId, LocalDate competencia, TipoDeLancamento tipo,
                        String origemTipo, UUID origemId, String contraparteTipo,
                        UUID contraparteId, Money valor, String descricao, UUID estornaId,
                        Instant criadoEm, UUID criadoPor) {
        return jdbc.sql("""
                insert into lancamento (estabelecimento_id, data_competencia, tipo, origem_tipo,
                                        origem_id, contraparte_tipo, contraparte_id, valor,
                                        descricao, estorna_lancamento_id, criado_em, criado_por)
                values (:estabelecimento, :competencia, :tipo, :origemTipo, :origemId,
                        :contraparteTipo, :contraparteId, :valor, :descricao, :estorna,
                        :criadoEm, :por)
                returning id
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("competencia", Date.valueOf(competencia))
                .param("tipo", tipo.name())
                .param("origemTipo", origemTipo)
                .param("origemId", origemId)
                .param("contraparteTipo", contraparteTipo)
                .param("contraparteId", contraparteId)
                .param("valor", valor.valor())
                .param("descricao", descricao)
                .param("estorna", estornaId)
                .param("criadoEm", OffsetDateTime.ofInstant(criadoEm, ZoneOffset.UTC))
                .param("por", criadoPor)
                .query(UUID.class)
                .single();
    }

    @Transactional(readOnly = true)
    public List<Lancamento> daOrigem(String origemTipo, UUID origemId) {
        return jdbc.sql("select " + CAMPOS + """
                  from lancamento
                 where origem_tipo = :tipo and origem_id = :id
                 order by criado_em, id
                """)
                .param("tipo", origemTipo)
                .param("id", origemId)
                .query(LancamentosJdbc::mapear)
                .list();
    }

    /**
     * As linhas de uma origem que <strong>ainda não foram estornadas</strong>.
     *
     * <p>É o que torna a compensação idempotente sem marca nenhuma: uma linha já anulada tem outra
     * apontando para ela, e o próprio razão responde se ainda há o que estornar. Mesma forma da
     * devolução de estoque (RN-ATD-022).
     */
    @Transactional(readOnly = true)
    public List<Lancamento> pendentesDeEstorno(String origemTipo, UUID origemId) {
        return jdbc.sql("select " + CAMPOS + """
                  from lancamento l
                 where l.origem_tipo = :tipo and l.origem_id = :id
                   and l.tipo <> 'ESTORNO'
                   and not exists (select 1 from lancamento e
                                    where e.estorna_lancamento_id = l.id)
                 order by l.criado_em, l.id
                """)
                .param("tipo", origemTipo)
                .param("id", origemId)
                .query(LancamentosJdbc::mapear)
                .list();
    }

    /** O saldo de uma contraparte no período. Sempre soma, nunca coluna. */
    @Transactional(readOnly = true)
    public Money saldoDaContraparte(String contraparteTipo, UUID contraparteId,
                                    LocalDate de, LocalDate ate) {
        return new Money(jdbc.sql("""
                select coalesce(sum(valor), 0) from lancamento
                 where contraparte_tipo = :tipo and contraparte_id = :id
                   and data_competencia between :de and :ate
                """)
                .param("tipo", contraparteTipo)
                .param("id", contraparteId)
                .param("de", Date.valueOf(de))
                .param("ate", Date.valueOf(ate))
                .query(java.math.BigDecimal.class)
                .single());
    }

    private static Lancamento mapear(ResultSet rs, int linha) throws SQLException {
        return new Lancamento(
                rs.getObject("id", UUID.class),
                rs.getDate("data_competencia").toLocalDate(),
                TipoDeLancamento.valueOf(rs.getString("tipo")),
                rs.getString("origem_tipo"),
                rs.getObject("origem_id", UUID.class),
                rs.getString("contraparte_tipo"),
                rs.getObject("contraparte_id", UUID.class),
                new Money(rs.getBigDecimal("valor")),
                rs.getString("descricao"),
                rs.getObject("estorna_lancamento_id", UUID.class));
    }
}
