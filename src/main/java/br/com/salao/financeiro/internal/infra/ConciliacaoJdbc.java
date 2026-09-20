package br.com.salao.financeiro.internal.infra;

import br.com.salao.financeiro.api.FormaDePagamento;
import br.com.salao.financeiro.internal.application.ConsultarConciliacaoUseCase.Item;
import br.com.salao.financeiro.internal.application.ConsultarConciliacaoUseCase.Recorte;
import br.com.salao.financeiro.internal.application.ConsultarConciliacaoUseCase.Totais;
import br.com.salao.financeiro.internal.domain.Casador;
import br.com.salao.financeiro.internal.domain.ProdutoDoCartao;
import br.com.salao.shared.jdbc.Colunas;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * RT-FIN-010 — o casamento no banco: os candidatos, os pares e o que a tela lê.
 *
 * <p>Nenhuma consulta daqui atravessa módulo: {@code pagamento}, {@code transacao_do_adquirente} e
 * {@code importacao_de_extrato} são todas do financeiro.
 */
public class ConciliacaoJdbc {

    /**
     * Os dias que alguma importação cobre, no fuso do salão. É o que separa "sem venda na maquininha" de
     * "sem extrato importado".
     *
     * <p>Entra no SQL pelo marcador {@code {coberto}}, e não somado ao fim de um bloco de texto: o bloco
     * apaga o espaço no fim da linha, e {@code "and " + COBERTO} virava {@code andexists} — o mesmo defeito
     * de RT-CNV-007, de novo.
     */
    private static final String COBERTO = " exists (select 1 from importacao_de_extrato i"
            + " where i.periodo_de is not null"
            + " and (p.criado_em at time zone :fuso)::date between i.periodo_de and i.periodo_ate) ";

    /**
     * RT-FIN-011 — o que já foi resolvido à mão sai da conferência (RN-FIN-033). Um por alvo, porque o
     * alias da tabela conferida muda em cada consulta.
     *
     * <p>Entram pelos marcadores {@code {parPendente}}, {@code {vendaPendente}} e
     * {@code {pagamentoPendente}}, pela mesma razão de {@link #COBERTO}.
     */
    private static final String PAR_PENDENTE = " not exists (select 1 from resolucao_de_conciliacao r"
            + " where r.conciliacao_id = c.id and r.reaberta_em is null) ";

    private static final String VENDA_PENDENTE = " not exists (select 1 from resolucao_de_conciliacao r"
            + " where r.transacao_id = t.id and r.reaberta_em is null) ";

    private static final String PAGAMENTO_PENDENTE =
            " not exists (select 1 from resolucao_de_conciliacao r"
            + " where r.pagamento_id = p.id and r.reaberta_em is null) ";

    /**
     * A venda de mesmo valor, no mesmo dia do salão, que foi cancelada na adquirente <em>depois de
     * casar</em> com um pagamento (RN-FIN-035).
     *
     * <p>É o sinal de "passou errado, cancelou, passou de novo": o valor desta venda já está no caixa
     * pela comanda do pagamento que casou com a cancelada, e lançá-lo de novo contaria duas vezes. A
     * tela avisa; quem decide é quem sabe se as duas vendas são a mesma.
     *
     * <p>Só canceladas que chegaram a casar. Cancelada antes da primeira importação nunca casou, e o
     * pagamento foi direto para a venda refeita — não há o que duplicar.
     */
    private static final String POSSIVEL_REFEITA = " exists (select 1 from conciliacao_de_venda c2"
            + " join transacao_do_adquirente t2 on t2.id = c2.transacao_id"
            + " where t2.situacao = 'CANCELADA' and t2.id <> t.id"
            + " and t2.valor_bruto = t.valor_bruto"
            + " and (t2.capturada_em at time zone :fuso)::date"
            + " = (t.capturada_em at time zone :fuso)::date) ";

    /** Troca os marcadores pelos trechos acima. Nunca some texto ao fim de um bloco. */
    private static String montar(String sql) {
        return sql.replace("{coberto}", COBERTO)
                .replace("{parPendente}", PAR_PENDENTE)
                .replace("{vendaPendente}", VENDA_PENDENTE)
                .replace("{pagamentoPendente}", PAGAMENTO_PENDENTE)
                .replace("{possivelRefeita}", POSSIVEL_REFEITA);
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ConciliacaoJdbc(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Pagamentos no cartão sem par, perto de algum período com extrato — um dia de folga para cada lado,
     * para a venda das 23h50 casar com o pagamento das 0h10.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Casador.Pagamento> pagamentosACasar(ZoneId fuso) {
        return jdbc.sql("""
                select p.id, p.valor, p.forma, p.criado_em, p.nsu, p.autorizacao, p.bandeira, p.parcelas
                  from pagamento p
                 where p.forma in ('CREDITO', 'DEBITO') and p.status_conciliacao = 'PENDENTE'
                   and not exists (select 1 from conciliacao_de_venda c where c.pagamento_id = p.id)
                   and exists (select 1 from importacao_de_extrato i
                                where i.periodo_de is not null
                                  and (p.criado_em at time zone :fuso)::date
                                      between i.periodo_de - 1 and i.periodo_ate + 1)
                """)
                .param("fuso", fuso.getId())
                .query((rs, n) -> new Casador.Pagamento(rs.getObject("id", UUID.class),
                        rs.getBigDecimal("valor"), FormaDePagamento.valueOf(rs.getString("forma")),
                        rs.getObject("criado_em", OffsetDateTime.class).toInstant(),
                        rs.getString("nsu"), rs.getString("autorizacao"), rs.getString("bandeira"),
                        rs.getInt("parcelas")))
                .list();
    }

    /** Vendas aprovadas sem par. Cancelada não é dinheiro, e não casa. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Casador.Venda> vendasACasar() {
        return jdbc.sql("""
                select t.id, t.valor_bruto, t.produto, t.capturada_em, t.hora_informada, t.nsu,
                       t.autorizacao, t.bandeira, t.parcelas
                  from transacao_do_adquirente t
                 where t.situacao = 'APROVADA'
                   and not exists (select 1 from conciliacao_de_venda c where c.transacao_id = t.id)
                """)
                .query((rs, n) -> new Casador.Venda(rs.getObject("id", UUID.class),
                        rs.getBigDecimal("valor_bruto"), produto(rs.getString("produto")),
                        rs.getObject("capturada_em", OffsetDateTime.class).toInstant(),
                        rs.getBoolean("hora_informada"), rs.getString("nsu"),
                        rs.getString("autorizacao"), rs.getString("bandeira"), rs.getInt("parcelas")))
                .list();
    }

    /**
     * Grava o par e o estado do pagamento, juntos. Falso se um dos dois lados já tinha par — outra rodada,
     * ao mesmo tempo, chegou antes.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean gravar(Casador.Par par, Instant agora) {
        boolean gravou = jdbc.sql("""
                insert into conciliacao_de_venda
                    (estabelecimento_id, pagamento_id, transacao_id, criterio, divergencias, casada_em)
                values (:estabelecimento, :pagamento, :venda, :criterio, cast(:divergencias as jsonb),
                        :agora)
                on conflict do nothing
                """)
                .param("estabelecimento", TenantContext.obrigatorio())
                .param("pagamento", par.pagamentoId())
                .param("venda", par.vendaId())
                .param("criterio", par.criterio().name())
                .param("divergencias", json.writeValueAsString(par.divergencias()))
                .param("agora", Timestamp.from(agora))
                .update() == 1;
        if (gravou) {
            jdbc.sql("""
                    update pagamento set status_conciliacao = :estado
                     where id = :pagamento and status_conciliacao = 'PENDENTE'
                    """)
                    .param("estado", par.divergente() ? "DIVERGENTE" : "CONCILIADO")
                    .param("pagamento", par.pagamentoId())
                    .update();
        }
        return gravou;
    }

    /**
     * O par cuja venda foi cancelada na adquirente depois de casado: ganha a divergência, e o pagamento
     * vira divergente. Num comando só — separados, uma queda deixaria um sem o outro.
     *
     * <p>RT-FIN-011 — e reabre a resolução do par, se havia uma. Ela foi tomada sem saber do
     * cancelamento: mantê-la esconderia da conferência dinheiro que o sistema conta e não vem, que é
     * exatamente o silêncio que ela existe para não ter. Nada do que a resolução fez é desfeito —
     * corrigir a forma continuou certo. É a pergunta que volta a ser feita, e a trilha guarda as duas.
     *
     * @return quantos pares foram marcados agora
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int marcarCanceladas(Instant agora) {
        return jdbc.sql("""
                with marcadas as (
                    update conciliacao_de_venda c
                       set divergencias = c.divergencias || '["CANCELADA_NA_ADQUIRENTE"]'::jsonb
                      from transacao_do_adquirente t
                     where t.id = c.transacao_id and t.situacao = 'CANCELADA'
                       and not c.divergencias @> '["CANCELADA_NA_ADQUIRENTE"]'::jsonb
                    returning c.id, c.pagamento_id),
                reabertas as (
                    update resolucao_de_conciliacao r
                       set reaberta_em = :agora, motivo_da_reabertura = :motivo
                      from marcadas m
                     where r.conciliacao_id = m.id and r.reaberta_em is null)
                update pagamento p set status_conciliacao = 'DIVERGENTE'
                  from marcadas m where p.id = m.pagamento_id
                """)
                .param("agora", Timestamp.from(agora))
                .param("motivo", MOTIVO_DA_REABERTURA_AUTOMATICA)
                .update();
    }

    /** Sem quem: {@code reaberta_por} nulo é o que distingue a reabertura do sistema (V60). */
    static final String MOTIVO_DA_REABERTURA_AUTOMATICA =
            "a venda foi cancelada na adquirente depois desta resolução";

    // ------------------------------------------------------------------ o que a tela lê

    /** As contas do período, cada uma em quantidade e valor. */
    public record Contas(Totais conciliadas, Totais divergentes, Totais soNaMaquininha,
                         Totais soNoSistema, Totais semExtrato) {
    }

    /** O período em {@code [de, ate)}: pela hora da venda nos pares e na maquininha, do registro no sistema. */
    @Transactional(readOnly = true)
    public Contas resumo(Instant de, Instant ate, ZoneId fuso) {
        return jdbc.sql(montar("""
                select
                  (select count(*) from conciliacao_de_venda c
                     join transacao_do_adquirente t on t.id = c.transacao_id
                    where t.capturada_em >= :de and t.capturada_em < :ate
                      and c.divergencias = '[]'::jsonb
                      and {parPendente}) as conciliadas_n,
                  (select coalesce(sum(t.valor_bruto), 0) from conciliacao_de_venda c
                     join transacao_do_adquirente t on t.id = c.transacao_id
                    where t.capturada_em >= :de and t.capturada_em < :ate
                      and c.divergencias = '[]'::jsonb
                      and {parPendente}) as conciliadas_v,
                  (select count(*) from conciliacao_de_venda c
                     join transacao_do_adquirente t on t.id = c.transacao_id
                    where t.capturada_em >= :de and t.capturada_em < :ate
                      and c.divergencias <> '[]'::jsonb
                      and {parPendente}) as divergentes_n,
                  (select coalesce(sum(t.valor_bruto), 0) from conciliacao_de_venda c
                     join transacao_do_adquirente t on t.id = c.transacao_id
                    where t.capturada_em >= :de and t.capturada_em < :ate
                      and c.divergencias <> '[]'::jsonb
                      and {parPendente}) as divergentes_v,
                  (select count(*) from transacao_do_adquirente t
                    where t.capturada_em >= :de and t.capturada_em < :ate and t.situacao = 'APROVADA'
                      and not exists (select 1 from conciliacao_de_venda c where c.transacao_id = t.id)
                      and {vendaPendente}) as maquininha_n,
                  (select coalesce(sum(t.valor_bruto), 0) from transacao_do_adquirente t
                    where t.capturada_em >= :de and t.capturada_em < :ate and t.situacao = 'APROVADA'
                      and not exists (select 1 from conciliacao_de_venda c where c.transacao_id = t.id)
                      and {vendaPendente}) as maquininha_v,
                  (select count(*) from pagamento p
                    where p.criado_em >= :de and p.criado_em < :ate
                      and p.forma in ('CREDITO', 'DEBITO') and p.status_conciliacao = 'PENDENTE'
                      and {pagamentoPendente} and {coberto}) as sistema_n,
                  (select coalesce(sum(p.valor), 0) from pagamento p
                    where p.criado_em >= :de and p.criado_em < :ate
                      and p.forma in ('CREDITO', 'DEBITO') and p.status_conciliacao = 'PENDENTE'
                      and {pagamentoPendente} and {coberto}) as sistema_v,
                  (select count(*) from pagamento p
                    where p.criado_em >= :de and p.criado_em < :ate
                      and p.forma in ('CREDITO', 'DEBITO') and p.status_conciliacao = 'PENDENTE'
                      and {pagamentoPendente} and not {coberto}) as sem_extrato_n,
                  (select coalesce(sum(p.valor), 0) from pagamento p
                    where p.criado_em >= :de and p.criado_em < :ate
                      and p.forma in ('CREDITO', 'DEBITO') and p.status_conciliacao = 'PENDENTE'
                      and {pagamentoPendente} and not {coberto}) as sem_extrato_v
                """))
                .param("de", Timestamp.from(de))
                .param("ate", Timestamp.from(ate))
                .param("fuso", fuso.getId())
                .query((rs, n) -> new Contas(
                        totais(rs, "conciliadas"), totais(rs, "divergentes"),
                        totais(rs, "maquininha"), totais(rs, "sistema"), totais(rs, "sem_extrato")))
                .single();
    }

    @Transactional(readOnly = true)
    public List<Item> itens(Recorte lado, Instant de, Instant ate, ZoneId fuso, Instant antesDe,
                            UUID antesDoId, int quantos) {
        String sql = switch (lado) {
            case CONCILIADAS, DIVERGENTES -> montar("""
                    select c.id, t.capturada_em as instante, t.id as venda_id, t.id_na_adquirente,
                           t.capturada_em, t.hora_informada, t.valor_bruto, t.produto, t.bandeira,
                           t.parcelas, p.id as pagamento_id, p.criado_em, p.valor, p.forma, c.criterio,
                           c.divergencias::text as divergencias, false as possivel_refeita
                      from conciliacao_de_venda c
                      join transacao_do_adquirente t on t.id = c.transacao_id
                      join pagamento p on p.id = c.pagamento_id
                     where t.capturada_em >= :de and t.capturada_em < :ate
                       and {parPendente}
                       and c.divergencias """) + (lado == Recorte.CONCILIADAS ? "=" : "<>") + """
                     '[]'::jsonb
                    """ + (antesDe == null ? "" : " and (t.capturada_em, c.id) < (:antesDe, :antesDoId)")
                    + " order by t.capturada_em desc, c.id desc limit :quantos";
            case SO_NA_MAQUININHA -> montar("""
                    select t.id, t.capturada_em as instante, t.id as venda_id, t.id_na_adquirente,
                           t.capturada_em, t.hora_informada, t.valor_bruto, t.produto, t.bandeira,
                           t.parcelas, null::uuid as pagamento_id, null::timestamptz as criado_em,
                           null::numeric as valor, null::text as forma, null::text as criterio,
                           null::text as divergencias, {possivelRefeita} as possivel_refeita
                      from transacao_do_adquirente t
                     where t.capturada_em >= :de and t.capturada_em < :ate and t.situacao = 'APROVADA'
                       and not exists (select 1 from conciliacao_de_venda c where c.transacao_id = t.id)
                       and {vendaPendente}
                    """) + (antesDe == null ? "" : " and (t.capturada_em, t.id) < (:antesDe, :antesDoId)")
                    + " order by t.capturada_em desc, t.id desc limit :quantos";
            case SO_NO_SISTEMA -> montar("""
                    select p.id, p.criado_em as instante, null::uuid as venda_id,
                           null::text as id_na_adquirente, null::timestamptz as capturada_em,
                           null::boolean as hora_informada, null::numeric as valor_bruto,
                           null::text as produto, p.bandeira, p.parcelas, p.id as pagamento_id,
                           p.criado_em, p.valor, p.forma, null::text as criterio,
                           null::text as divergencias, false as possivel_refeita
                      from pagamento p
                     where p.criado_em >= :de and p.criado_em < :ate
                       and p.forma in ('CREDITO', 'DEBITO') and p.status_conciliacao = 'PENDENTE'
                       and {pagamentoPendente} and {coberto}
                    """) + (antesDe == null ? "" : " and (p.criado_em, p.id) < (:antesDe, :antesDoId)")
                    + " order by p.criado_em desc, p.id desc limit :quantos";
        };
        var comando = jdbc.sql(sql)
                .param("de", Timestamp.from(de))
                .param("ate", Timestamp.from(ate))
                .param("quantos", quantos);
        if (lado == Recorte.SO_NO_SISTEMA || lado == Recorte.SO_NA_MAQUININHA) {
            comando = comando.param("fuso", fuso.getId());
        }
        if (antesDe != null) {
            comando = comando.param("antesDe", Timestamp.from(antesDe)).param("antesDoId", antesDoId);
        }
        return comando.query(this::item).list();
    }

    private Item item(ResultSet rs, int n) throws SQLException {
        String divergencias = rs.getString("divergencias");
        List<String> lidas = new ArrayList<>();
        if (divergencias != null) {
            for (JsonNode d : json.readTree(divergencias)) {
                lidas.add(d.asString());
            }
        }
        return new Item(rs.getObject("id", UUID.class),
                rs.getObject("instante", OffsetDateTime.class).toInstant(),
                rs.getObject("venda_id", UUID.class), rs.getString("id_na_adquirente"),
                instante(rs, "capturada_em"), rs.getObject("hora_informada", Boolean.class),
                rs.getBigDecimal("valor_bruto"), rs.getString("produto"), rs.getString("bandeira"),
                Colunas.inteiroOuNulo(rs, "parcelas"), rs.getObject("pagamento_id", UUID.class),
                instante(rs, "criado_em"), rs.getBigDecimal("valor"), rs.getString("forma"),
                rs.getString("criterio"), lidas, rs.getBoolean("possivel_refeita"));
    }

    private static Instant instante(ResultSet rs, String coluna) throws SQLException {
        OffsetDateTime valor = rs.getObject(coluna, OffsetDateTime.class);
        return valor == null ? null : valor.toInstant();
    }

    private static Totais totais(ResultSet rs, String prefixo) throws SQLException {
        return new Totais(rs.getInt(prefixo + "_n"), rs.getBigDecimal(prefixo + "_v"));
    }

    private static ProdutoDoCartao produto(String texto) {
        return texto == null ? null : ProdutoDoCartao.valueOf(texto);
    }
}
