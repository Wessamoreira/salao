package br.com.salao.financeiro.internal.infra;

import br.com.salao.financeiro.api.FechamentoDoProfissional;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-006 — os fechamentos.
 *
 * <p>Só {@code insert} e {@code select}, como o razão e o vale: a role da aplicação não tem
 * {@code update} nem {@code delete} nesta tabela (V42).
 */
public class FechamentosJdbc {

    private static final String CAMPOS = """
            id, profissional_id, periodo_inicio, periodo_fim, saldo_anterior,
            movimento_do_periodo, valor_repassado, criado_em, estornado_em
            """;

    private final JdbcClient jdbc;

    public FechamentosJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Serializa fechamentos do mesmo estabelecimento durante a transação atual.
     *
     * <p>A constraint de sobreposição protege o mesmo profissional e intervalo, mas não impede
     * março e abril de serem calculados ao mesmo tempo sobre o mesmo saldo acumulado. O advisory
     * lock fecha essa corrida sem acessar a tabela {@code estabelecimento}, que pertence ao IAM.
     */
    /*
     * MANDATORY, e não o `@Transactional` padrão.
     *
     * `pg_advisory_xact_lock` vive pela TRANSAÇÃO: ele é liberado no commit. Chamado fora de
     * uma, o Spring abriria uma transação só para esta linha, o lock seria adquirido e
     * devolvido no mesmo instante — e o método retornaria normalmente, sem proteger nada.
     *
     * Uma trava que silenciosamente não trava é pior do que trava nenhuma: o teste de
     * concorrência continuaria verde por acidente de temporização. MANDATORY transforma o
     * engano em exceção na primeira chamada.
     *
     * É a mesma família de lição de RT-EST-004 e RT-INF-002: anotação de transação só vale
     * onde o Spring consegue interceptar, e o que depende da transação precisa exigi-la.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void travarEstabelecimento(UUID estabelecimentoId) {
        jdbc.sql("""
                select pg_advisory_xact_lock(
                    hashtextextended(cast(:estabelecimento as text), 0))
                """)
                .param("estabelecimento", estabelecimentoId)
                .query((rs, linha) -> Boolean.TRUE)
                .single();
    }

    @Transactional
    public UUID inserir(UUID estabelecimentoId, UUID profissionalId, LocalDate inicio,
                        LocalDate fim, Money saldoAnterior, Money movimento, Money repassado,
                        Instant criadoEm, UUID criadoPor) {
        return jdbc.sql("""
                insert into fechamento (estabelecimento_id, profissional_id, periodo_inicio,
                                        periodo_fim, saldo_anterior, movimento_do_periodo,
                                        valor_repassado, criado_em, criado_por)
                values (:estabelecimento, :profissional, :inicio, :fim, :anterior, :movimento,
                        :repassado, :criadoEm, :por)
                returning id
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("profissional", profissionalId)
                .param("inicio", Date.valueOf(inicio))
                .param("fim", Date.valueOf(fim))
                .param("anterior", saldoAnterior.valor())
                .param("movimento", movimento.valor())
                .param("repassado", repassado.valor())
                .param("criadoEm", OffsetDateTime.ofInstant(criadoEm, ZoneOffset.UTC))
                .param("por", criadoPor)
                .query(UUID.class)
                .single();
    }

    /**
     * A data final do último fechamento do salão, se houver.
     *
     * <p>É o que impede fechar para trás. O {@code EXCLUDE} do banco recusa períodos que se
     * sobrepõem, mas não recusa fechar março <em>depois</em> de abril — e aí a soma acumulada até
     * 31 de março não enxergaria o repasse de abril, e pagaria março de novo.
     */
    @Transactional(readOnly = true)
    public Optional<FechamentoDoProfissional> porId(UUID id) {
        return jdbc.sql("select " + CAMPOS + " from fechamento where id = :id")
                .param("id", id)
                .query(FechamentosJdbc::mapear)
                .optional();
    }

    /**
     * Anula o documento e devolve o período.
     *
     * <p>O único {@code update} que esta tabela aceita, e ele alcança UMA coluna: a V43 concede
     * {@code update (estornado_em)} e mais nada. Tentar mexer em {@code valor_repassado} continua
     * sendo recusado pelo banco (RN-FIN-020) — não por disciplina de quem escreve o código.
     *
     * <p>O {@code where estornado_em is null} torna a operação idempotente no próprio SQL:
     * estornar duas vezes não sobrescreve a data do primeiro estorno.
     */
    @Transactional
    public int marcarEstornado(UUID id, Instant quando) {
        return jdbc.sql("""
                update fechamento set estornado_em = :quando
                 where id = :id and estornado_em is null
                """)
                .param("quando", OffsetDateTime.ofInstant(quando, ZoneOffset.UTC))
                .param("id", id)
                .update();
    }

    /**
     * A data final do último fechamento VIGENTE do salão.
     *
     * <p>Estornado não conta. Se contasse, um fechamento anulado continuaria impedindo o período
     * de ser refeito — e a liberação não liberaria nada.
     */
    @Transactional(readOnly = true)
    public Optional<LocalDate> ultimoFim() {
        return jdbc.sql("select max(periodo_fim) from fechamento where estornado_em is null")
                .query(Date.class)
                .optional()
                .map(Date::toLocalDate);
    }

    @Transactional(readOnly = true)
    public List<FechamentoDoProfissional> doPeriodo(UUID profissionalId, LocalDate de,
                                                    LocalDate ate) {
        return jdbc.sql("select " + CAMPOS + """
                  from fechamento
                 where periodo_inicio >= :de and periodo_fim <= :ate
                   and (cast(:profissional as uuid) is null
                        or profissional_id = :profissional)
                 order by periodo_fim desc, periodo_inicio desc, criado_em, id
                """)
                .param("profissional", profissionalId)
                .param("de", Date.valueOf(de))
                .param("ate", Date.valueOf(ate))
                .query(FechamentosJdbc::mapear)
                .list();
    }

    private static FechamentoDoProfissional mapear(ResultSet rs, int linha) throws SQLException {
        return new FechamentoDoProfissional(
                rs.getObject("id", UUID.class),
                rs.getObject("profissional_id", UUID.class),
                rs.getDate("periodo_inicio").toLocalDate(),
                rs.getDate("periodo_fim").toLocalDate(),
                new Money(rs.getBigDecimal("saldo_anterior")),
                new Money(rs.getBigDecimal("movimento_do_periodo")),
                new Money(rs.getBigDecimal("valor_repassado")),
                rs.getObject("criado_em", OffsetDateTime.class).toInstant(),
                // Anulável: `getObject` devolve null direto, sem a armadilha do `wasNull`.
                Optional.ofNullable(rs.getObject("estornado_em", OffsetDateTime.class))
                        .map(OffsetDateTime::toInstant).orElse(null));
    }
}
