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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * As linhas de uma contraparte no período, em ordem de competência.
     *
     * <p>Todas, inclusive os estornos: uma comanda reaberta e refechada deixa seis lançamentos, e
     * esconder quatro faria o total não bater com nada que a pessoa consiga conferir.
     */
    @Transactional(readOnly = true)
    public List<Lancamento> daContraparte(String contraparteTipo, UUID contraparteId,
                                          LocalDate de, LocalDate ate) {
        return jdbc.sql("select " + CAMPOS + """
                  from lancamento
                 where contraparte_tipo = :tipo and contraparte_id = :id
                   and data_competencia between :de and :ate
                 order by data_competencia, criado_em, id
                """)
                .param("tipo", contraparteTipo)
                .param("id", contraparteId)
                .param("de", Date.valueOf(de))
                .param("ate", Date.valueOf(ate))
                .query(LancamentosJdbc::mapear)
                .list();
    }

    /**
     * O saldo <strong>acumulado</strong> de uma contraparte até uma data, do começo de tudo.
     *
     * <p>É a conta do fechamento (RT-FIN-006), e o "até" no lugar do "entre" é o que faz a dívida
     * de quem adiantou demais atravessar o mês sem linha de transporte nenhuma: os {@code REPASSE}
     * já feitos estão dentro da mesma soma, zerando o que já foi pago.
     */
    @Transactional(readOnly = true)
    public Money saldoAcumuladoAte(String contraparteTipo, UUID contraparteId, LocalDate ate) {
        return new Money(jdbc.sql("""
                select coalesce(sum(valor), 0) from lancamento
                 where contraparte_tipo = :tipo and contraparte_id = :id
                   and data_competencia <= :ate
                """)
                .param("tipo", contraparteTipo)
                .param("id", contraparteId)
                .param("ate", Date.valueOf(ate))
                .query(java.math.BigDecimal.class)
                .single());
    }

    /**
     * O saldo acumulado de <strong>todas</strong> as contrapartes de um tipo até uma data.
     *
     * <p>Uma consulta agrupada e não uma por pessoa: o fechamento pergunta pelo salão inteiro, e
     * um laço de {@code saldoAcumuladoAte} seria N consultas para produzir a mesma tabela que o
     * banco já sabe agrupar. É a mesma razão de {@code ProfissionalApi.intervalosEm(Collection,
     * ...)}.
     *
     * <p>Quem não tem lançamento nenhum não aparece no mapa — ausente significa zero, e quem itera
     * sobre as chaves já pula quem não tem posição.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Money> saldosAcumuladosAte(String contraparteTipo, LocalDate ate) {
        return agrupar(jdbc.sql("""
                select contraparte_id, coalesce(sum(valor), 0) as saldo from lancamento
                 where contraparte_tipo = :tipo and contraparte_id is not null
                   and data_competencia <= :ate
                 group by contraparte_id
                """)
                .param("tipo", contraparteTipo)
                .param("ate", Date.valueOf(ate)));
    }

    /** O mesmo agrupamento, restrito a um período. */
    @Transactional(readOnly = true)
    public Map<UUID, Money> saldosNoPeriodo(String contraparteTipo, LocalDate de, LocalDate ate) {
        return agrupar(jdbc.sql("""
                select contraparte_id, coalesce(sum(valor), 0) as saldo from lancamento
                 where contraparte_tipo = :tipo and contraparte_id is not null
                   and data_competencia between :de and :ate
                 group by contraparte_id
                """)
                .param("tipo", contraparteTipo)
                .param("de", Date.valueOf(de))
                .param("ate", Date.valueOf(ate)));
    }

    private static Map<UUID, Money> agrupar(JdbcClient.StatementSpec consulta) {
        Map<UUID, Money> saldos = new LinkedHashMap<>();
        consulta.query((ResultSet rs) -> {
            saldos.put(rs.getObject("contraparte_id", UUID.class),
                    new Money(rs.getBigDecimal("saldo")));
        });
        return saldos;
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
