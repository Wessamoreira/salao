package br.com.salao.financeiro.internal.infra;

import br.com.salao.financeiro.api.FormaDePagamento;
import br.com.salao.financeiro.internal.application.ConsultarConciliacaoUseCase.Totais;
import br.com.salao.financeiro.internal.domain.AcaoDaResolucao;
import br.com.salao.financeiro.internal.domain.AlvoDaResolucao;
import br.com.salao.financeiro.internal.domain.Divergencia;
import br.com.salao.financeiro.internal.domain.EfeitoDaResolucao;
import br.com.salao.financeiro.internal.domain.ProdutoDoCartao;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * RT-FIN-011 — as resoluções no banco: o alvo que vai ser resolvido, a gravação e a trilha.
 *
 * <p>Nenhuma consulta daqui atravessa módulo — {@code pagamento}, {@code transacao_do_adquirente},
 * {@code conciliacao_de_venda} e {@code resolucao_de_conciliacao} são todas do financeiro.
 */
public class ResolucoesJdbc {

    /** Sinaliza que o alvo já tinha resolução valendo: dois cliques, ou duas abas. */
    public static class JaResolvidoException extends RuntimeException {
        public JaResolvidoException(Throwable causa) {
            super(causa);
        }
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ResolucoesJdbc(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ------------------------------------------------------------------ os alvos

    /** O par e os dois lados dele, como estão agora. */
    public record ParACorrigir(UUID id, Set<Divergencia> divergencias,
                               ProdutoDoCartao produto, String bandeiraDaVenda, int parcelasDaVenda,
                               BigDecimal valorDaVenda, UUID pagamentoId, FormaDePagamento forma,
                               String bandeira, int parcelas, String nsu, String autorizacao,
                               BigDecimal valorDoPagamento, String status) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ParACorrigir> par(UUID id) {
        return jdbc.sql("""
                select c.id, c.divergencias::text as divergencias,
                       t.produto, t.bandeira as bandeira_venda, t.parcelas as parcelas_venda,
                       t.valor_bruto, p.id as pagamento_id, p.forma, p.bandeira, p.parcelas,
                       p.nsu, p.autorizacao, p.valor, p.status_conciliacao
                  from conciliacao_de_venda c
                  join transacao_do_adquirente t on t.id = c.transacao_id
                  join pagamento p on p.id = c.pagamento_id
                 where c.id = :id
                """)
                .param("id", id)
                .query((rs, n) -> new ParACorrigir(rs.getObject("id", UUID.class),
                        divergencias(rs.getString("divergencias")), produto(rs.getString("produto")),
                        rs.getString("bandeira_venda"), rs.getInt("parcelas_venda"),
                        rs.getBigDecimal("valor_bruto"), rs.getObject("pagamento_id", UUID.class),
                        FormaDePagamento.valueOf(rs.getString("forma")), rs.getString("bandeira"),
                        rs.getInt("parcelas"), rs.getString("nsu"), rs.getString("autorizacao"),
                        rs.getBigDecimal("valor"), rs.getString("status_conciliacao")))
                .optional();
    }

    /** @param temPar se a venda já casou com algum pagamento — então não é "só na maquininha" */
    public record VendaAResolver(UUID id, BigDecimal valor, Instant capturadaEm, String situacao,
                                 boolean temPar) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<VendaAResolver> venda(UUID id) {
        return jdbc.sql("""
                select t.id, t.valor_bruto, t.capturada_em, t.situacao,
                       exists (select 1 from conciliacao_de_venda c where c.transacao_id = t.id)
                         as tem_par
                  from transacao_do_adquirente t
                 where t.id = :id
                """)
                .param("id", id)
                .query((rs, n) -> new VendaAResolver(rs.getObject("id", UUID.class),
                        rs.getBigDecimal("valor_bruto"), instante(rs, "capturada_em"),
                        rs.getString("situacao"), rs.getBoolean("tem_par")))
                .optional();
    }

    public record PagamentoAResolver(UUID id, BigDecimal valor, FormaDePagamento forma,
                                     String bandeira, int parcelas, String nsu, String autorizacao,
                                     String status, boolean temPar) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PagamentoAResolver> pagamento(UUID id) {
        return jdbc.sql("""
                select p.id, p.valor, p.forma, p.bandeira, p.parcelas, p.nsu, p.autorizacao,
                       p.status_conciliacao,
                       exists (select 1 from conciliacao_de_venda c where c.pagamento_id = p.id)
                         as tem_par
                  from pagamento p
                 where p.id = :id
                """)
                .param("id", id)
                .query((rs, n) -> new PagamentoAResolver(rs.getObject("id", UUID.class),
                        rs.getBigDecimal("valor"),
                        FormaDePagamento.valueOf(rs.getString("forma")), rs.getString("bandeira"),
                        rs.getInt("parcelas"), rs.getString("nsu"), rs.getString("autorizacao"),
                        rs.getString("status_conciliacao"), rs.getBoolean("tem_par")))
                .optional();
    }

    // ------------------------------------------------------------------ escrever

    /**
     * Grava a resolução. O id vem de fora porque o lançamento no razão já nasceu apontando para ele —
     * é a origem que a reabertura usa para estornar.
     *
     * @throws JaResolvidoException se o alvo já tinha resolução valendo. Quem garante é o índice
     *                              parcial (V60), e não uma leitura antes: duas abas leem as duas
     *                              "não resolvido"
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void gravar(UUID id, AlvoDaResolucao alvo, UUID alvoId, AcaoDaResolucao acao,
                       String motivo, EfeitoDaResolucao efeito, UUID lancamentoId, Instant agora,
                       UUID por) {
        try {
            jdbc.sql("""
                    insert into resolucao_de_conciliacao
                        (id, estabelecimento_id, conciliacao_id, transacao_id, pagamento_id, acao,
                         motivo, efeito, lancamento_id, resolvida_em, resolvida_por)
                    values (:id, :estabelecimento, :par, :venda, :pagamento, :acao, :motivo,
                            cast(:efeito as jsonb), :lancamento, :agora, :por)
                    """)
                    .param("id", id)
                    .param("estabelecimento", TenantContext.obrigatorio())
                    .param("par", alvo == AlvoDaResolucao.PAR ? alvoId : null)
                    .param("venda", alvo == AlvoDaResolucao.VENDA ? alvoId : null)
                    .param("pagamento", alvo == AlvoDaResolucao.PAGAMENTO ? alvoId : null)
                    .param("acao", acao.name())
                    .param("motivo", motivo)
                    .param("efeito", json.writeValueAsString(efeito))
                    .param("lancamento", lancamentoId)
                    .param("agora", Timestamp.from(agora))
                    .param("por", por)
                    .update();
        } catch (DuplicateKeyException e) {
            throw new JaResolvidoException(e);
        }
    }

    /** Marca a resolução como reaberta. Falso se outra reabertura chegou antes. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean reabrir(UUID id, String motivo, Instant agora, UUID por) {
        return jdbc.sql("""
                update resolucao_de_conciliacao
                   set reaberta_em = :agora, reaberta_por = :por, motivo_da_reabertura = :motivo
                 where id = :id and reaberta_em is null
                """)
                .param("id", id)
                .param("motivo", motivo)
                .param("agora", Timestamp.from(agora))
                .param("por", por)
                .update() == 1;
    }

    /**
     * A classificação do pagamento, e só ela. Valor nunca — dinheiro recebido não se reescreve
     * (RN-FIN-034); a diferença vira lançamento no razão.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void classificarPagamento(UUID pagamentoId, FormaDePagamento forma, String bandeira,
                                     int parcelas, String nsu, String autorizacao, String status) {
        jdbc.sql("""
                update pagamento
                   set forma = :forma, bandeira = :bandeira, parcelas = :parcelas, nsu = :nsu,
                       autorizacao = :autorizacao, status_conciliacao = :status
                 where id = :id
                """)
                .param("id", pagamentoId)
                .param("forma", forma.name())
                .param("bandeira", bandeira)
                .param("parcelas", parcelas)
                .param("nsu", nsu)
                .param("autorizacao", autorizacao)
                .param("status", status)
                .update();
    }

    // ------------------------------------------------------------------ ler

    public record Resolucao(UUID id, UUID conciliacaoId, UUID transacaoId, UUID pagamentoId,
                            AcaoDaResolucao acao, String motivo, EfeitoDaResolucao efeito,
                            UUID lancamentoId, Instant resolvidaEm, UUID resolvidaPor,
                            Instant reabertaEm) {

        public AlvoDaResolucao alvo() {
            if (conciliacaoId != null) {
                return AlvoDaResolucao.PAR;
            }
            return transacaoId != null ? AlvoDaResolucao.VENDA : AlvoDaResolucao.PAGAMENTO;
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Resolucao> porId(UUID id) {
        return jdbc.sql("""
                select id, conciliacao_id, transacao_id, pagamento_id, acao, motivo,
                       efeito::text as efeito, lancamento_id, resolvida_em, resolvida_por,
                       reaberta_em
                  from resolucao_de_conciliacao
                 where id = :id
                """)
                .param("id", id)
                .query(this::resolucao)
                .optional();
    }

    /**
     * A trilha do período. O período é o da <strong>linha conferida</strong> — a venda, ou o pagamento
     * quando não há venda —, e não o da decisão: quem abre setembro quer o que foi resolvido de
     * setembro, tenha sido decidido em outubro.
     */
    public record LinhaDaTrilha(UUID id, Instant resolvidaEm, UUID resolvidaPor,
                                AcaoDaResolucao acao, String motivo, AlvoDaResolucao alvo,
                                Instant instante, BigDecimal valor, String idNaAdquirente,
                                List<String> divergencias, EfeitoDaResolucao efeito) {
    }

    /** O instante da linha conferida, qualquer que seja o alvo. Repetido por não caber em parâmetro. */
    private static final String INSTANTE =
            " coalesce(tp.capturada_em, tv.capturada_em, pp.criado_em) ";

    private static final String ALVOS = """
              from resolucao_de_conciliacao r
              left join conciliacao_de_venda c on c.id = r.conciliacao_id
              left join transacao_do_adquirente tp on tp.id = c.transacao_id
              left join transacao_do_adquirente tv on tv.id = r.transacao_id
              left join pagamento pp on pp.id = r.pagamento_id
             where r.reaberta_em is null
            """;

    @Transactional(readOnly = true)
    public List<LinhaDaTrilha> listar(Instant de, Instant ate, Instant antesDe, UUID antesDoId,
                                      int quantos) {
        String sql = "select r.id, r.resolvida_em, r.resolvida_por, r.acao, r.motivo,"
                + " r.efeito::text as efeito, c.divergencias::text as divergencias,"
                + " r.conciliacao_id, r.transacao_id,"
                + INSTANTE + "as instante,"
                + " coalesce(tp.valor_bruto, tv.valor_bruto, pp.valor) as valor,"
                + " coalesce(tp.id_na_adquirente, tv.id_na_adquirente) as id_na_adquirente"
                + ALVOS
                + " and" + INSTANTE + ">= :de and" + INSTANTE + "< :ate"
                + (antesDe == null ? "" : " and (r.resolvida_em, r.id) < (:antesDe, :antesDoId)")
                + " order by r.resolvida_em desc, r.id desc limit :quantos";
        var comando = jdbc.sql(sql)
                .param("de", Timestamp.from(de))
                .param("ate", Timestamp.from(ate))
                .param("quantos", quantos);
        if (antesDe != null) {
            comando = comando.param("antesDe", Timestamp.from(antesDe)).param("antesDoId", antesDoId);
        }
        return comando.query((rs, n) -> new LinhaDaTrilha(rs.getObject("id", UUID.class),
                        instante(rs, "resolvida_em"), rs.getObject("resolvida_por", UUID.class),
                        AcaoDaResolucao.valueOf(rs.getString("acao")), rs.getString("motivo"),
                        alvo(rs), instante(rs, "instante"), rs.getBigDecimal("valor"),
                        rs.getString("id_na_adquirente"),
                        divergencias(rs.getString("divergencias")).stream()
                                .map(Enum::name).toList(),
                        efeito(rs.getString("efeito"))))
                .list();
    }

    /** Quantas e quanto — a quinta conta da conferência. */
    @Transactional(readOnly = true)
    public Totais contarResolvidas(Instant de, Instant ate) {
        return jdbc.sql("select count(*) as n, coalesce(sum("
                + " coalesce(tp.valor_bruto, tv.valor_bruto, pp.valor)), 0) as v"
                + ALVOS + " and" + INSTANTE + ">= :de and" + INSTANTE + "< :ate")
                .param("de", Timestamp.from(de))
                .param("ate", Timestamp.from(ate))
                .query((rs, n) -> new Totais(rs.getInt("n"), rs.getBigDecimal("v")))
                .single();
    }

    private Resolucao resolucao(ResultSet rs, int n) throws SQLException {
        return new Resolucao(rs.getObject("id", UUID.class),
                rs.getObject("conciliacao_id", UUID.class),
                rs.getObject("transacao_id", UUID.class),
                rs.getObject("pagamento_id", UUID.class),
                AcaoDaResolucao.valueOf(rs.getString("acao")), rs.getString("motivo"),
                efeito(rs.getString("efeito")), rs.getObject("lancamento_id", UUID.class),
                instante(rs, "resolvida_em"), rs.getObject("resolvida_por", UUID.class),
                instante(rs, "reaberta_em"));
    }

    private static AlvoDaResolucao alvo(ResultSet rs) throws SQLException {
        if (rs.getObject("conciliacao_id", UUID.class) != null) {
            return AlvoDaResolucao.PAR;
        }
        return rs.getObject("transacao_id", UUID.class) != null
                ? AlvoDaResolucao.VENDA : AlvoDaResolucao.PAGAMENTO;
    }

    private EfeitoDaResolucao efeito(String texto) {
        return texto == null ? EfeitoDaResolucao.NENHUM
                : json.readValue(texto, EfeitoDaResolucao.class);
    }

    private Set<Divergencia> divergencias(String texto) {
        var lidas = EnumSet.noneOf(Divergencia.class);
        if (texto != null) {
            for (JsonNode no : json.readTree(texto)) {
                lidas.add(Divergencia.valueOf(no.asString()));
            }
        }
        return lidas;
    }

    private static Instant instante(ResultSet rs, String coluna) throws SQLException {
        OffsetDateTime valor = rs.getObject(coluna, OffsetDateTime.class);
        return valor == null ? null : valor.toInstant();
    }

    private static ProdutoDoCartao produto(String texto) {
        return texto == null ? null : ProdutoDoCartao.valueOf(texto);
    }
}
