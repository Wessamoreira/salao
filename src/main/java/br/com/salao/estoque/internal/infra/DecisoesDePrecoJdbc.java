package br.com.salao.estoque.internal.infra;

import br.com.salao.estoque.internal.domain.MotivoDaSugestao;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** RT-EST-011 — o que foi decidido sobre cada sugestão, e com que informação. */
public class DecisoesDePrecoJdbc {

    private final JdbcClient jdbc;

    public DecisoesDePrecoJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @param precoAplicado nulo na recusa; @param justificativa nula na aplicação */
    public record Nova(UUID produtoId, String decisao, MotivoDaSugestao motivo,
                       BigDecimal precoAnterior, BigDecimal custoMedio, BigDecimal precoSugerido,
                       BigDecimal precoAplicado, String racional, String justificativa) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID registrar(Nova decisao, Instant agora, UUID por) {
        return jdbc.sql("""
                insert into decisao_de_preco (estabelecimento_id, produto_id, decisao, motivo,
                        preco_anterior, custo_medio, preco_sugerido, preco_aplicado, racional,
                        justificativa, decidida_em, decidida_por)
                values (:estabelecimento, :produto, :decisao, :motivo, :anterior, :custo,
                        :sugerido, :aplicado, :racional, :justificativa, :agora, :por)
                returning id
                """)
                .param("estabelecimento", TenantContext.obrigatorio())
                .param("produto", decisao.produtoId())
                .param("decisao", decisao.decisao())
                .param("motivo", decisao.motivo().name())
                .param("anterior", decisao.precoAnterior())
                .param("custo", decisao.custoMedio())
                .param("sugerido", decisao.precoSugerido())
                .param("aplicado", decisao.precoAplicado())
                .param("racional", decisao.racional())
                .param("justificativa", decisao.justificativa())
                .param("agora", Timestamp.from(agora))
                .param("por", por)
                .query(UUID.class)
                .single();
    }

    /** O que o dono recusou e ainda está calado (RN-EST-035). */
    public record Silencio(UUID produtoId, MotivoDaSugestao motivo, BigDecimal precoSugerido) {
    }

    /**
     * A recusa mais recente de cada produto desde {@code desde}.
     *
     * <p>Só a mais recente: se o dono recusou duas vezes com sugestões diferentes, quem cala é a
     * última — ela é a que descreve o que ele viu por último e dispensou.
     */
    @Transactional(readOnly = true)
    public List<Silencio> recusasDesde(Instant desde) {
        return jdbc.sql("""
                select distinct on (produto_id) produto_id, motivo, preco_sugerido
                  from decisao_de_preco
                 where decisao = 'RECUSADA' and decidida_em >= :desde
                 order by produto_id, decidida_em desc
                """)
                .param("desde", Timestamp.from(desde))
                .query((rs, n) -> new Silencio(rs.getObject("produto_id", UUID.class),
                        MotivoDaSugestao.valueOf(rs.getString("motivo")),
                        rs.getBigDecimal("preco_sugerido")))
                .list();
    }

    // ------------------------------------------------------------------ a trilha

    public record Linha(UUID id, UUID produtoId, String produto, String decisao,
                        MotivoDaSugestao motivo, BigDecimal precoAnterior, BigDecimal custoMedio,
                        BigDecimal precoSugerido, BigDecimal precoAplicado, String racional,
                        String justificativa, Instant decididaEm, UUID decididaPor) {
    }

    /** Das mais recentes para trás, por keyset — {@code OFFSET} é proibido no projeto. */
    @Transactional(readOnly = true)
    public List<Linha> listar(Instant antesDe, UUID antesDoId, int quantos) {
        String sql = """
                select d.id, d.produto_id, p.nome, d.decisao, d.motivo, d.preco_anterior,
                       d.custo_medio, d.preco_sugerido, d.preco_aplicado, d.racional,
                       d.justificativa, d.decidida_em, d.decidida_por
                  from decisao_de_preco d
                  join produto p on p.id = d.produto_id
                """
                + (antesDe == null ? "" : " where (d.decidida_em, d.id) < (:antesDe, :antesDoId)")
                + " order by d.decidida_em desc, d.id desc limit :quantos";
        var comando = jdbc.sql(sql).param("quantos", quantos);
        if (antesDe != null) {
            comando = comando.param("antesDe", Timestamp.from(antesDe)).param("antesDoId", antesDoId);
        }
        return comando.query((rs, n) -> new Linha(rs.getObject("id", UUID.class),
                        rs.getObject("produto_id", UUID.class), rs.getString("nome"),
                        rs.getString("decisao"),
                        MotivoDaSugestao.valueOf(rs.getString("motivo")),
                        rs.getBigDecimal("preco_anterior"), rs.getBigDecimal("custo_medio"),
                        rs.getBigDecimal("preco_sugerido"), rs.getBigDecimal("preco_aplicado"),
                        rs.getString("racional"), rs.getString("justificativa"),
                        rs.getObject("decidida_em", OffsetDateTime.class).toInstant(),
                        rs.getObject("decidida_por", UUID.class)))
                .list();
    }
}
