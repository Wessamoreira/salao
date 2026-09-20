package br.com.salao.estoque.internal.infra;

import br.com.salao.estoque.internal.domain.MotivoDaSugestao;
import br.com.salao.estoque.internal.domain.MotorDePreco;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.jdbc.Colunas;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** RT-EST-010 — o que o motor precisa ler, e onde a sugestão fica. */
public class SugestoesDePrecoJdbc {

    private final JdbcClient jdbc;

    public SugestoesDePrecoJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Os produtos de venda ativos, com saldo, custo médio, preço e há quantos dias não vendem.
     *
     * <h2>Custo médio pela mesma conta de RT-EST-003</h2>
     *
     * <p>Ponderação só dos lotes com saldo positivo; nulo quando não há estoque. Repetir o
     * {@code case} aqui em vez de chamar a consulta de saldo é o que permite trazer preço e dias sem
     * venda na mesma varredura — a alternativa seria uma consulta por produto para desenhar a tela.
     *
     * <h2>Dias sem venda, e o que fazer com quem nunca vendeu</h2>
     *
     * <p>Desde a última venda; quando nunca houve venda, desde o primeiro movimento — foi quando o
     * produto chegou à prateleira. Produto que nunca se moveu devolve nulo, e o motor não o encalha:
     * não há desde quando contar.
     */
    @Transactional(readOnly = true)
    public List<MotorDePreco.Produto> produtosAAvaliar(ZoneId fuso, LocalDate hoje) {
        return jdbc.sql("""
                with saldo_por_lote as (
                  select l.produto_id, l.custo_unitario,
                         coalesce((select sum(m.quantidade) from movimento_estoque m
                                    where m.lote_id = l.id), 0) as saldo
                    from lote l
                ),
                ultimo_movimento as (
                  select m.produto_id,
                         max(m.criado_em) filter (where m.tipo = 'VENDA') as ultima_venda,
                         min(m.criado_em) as primeiro
                    from movimento_estoque m
                   group by m.produto_id
                )
                select p.id as produto_id, p.nome, p.preco_venda,
                       coalesce(sum(s.saldo), 0) as quantidade,
                       case when sum(case when s.saldo > 0 then s.saldo end) > 0
                            then sum(case when s.saldo > 0 then s.saldo * s.custo_unitario end)
                               / sum(case when s.saldo > 0 then s.saldo end)
                       end as custo_medio,
                       max(coalesce(u.ultima_venda, u.primeiro)) as referencia
                  from produto p
                  left join saldo_por_lote s on s.produto_id = p.id
                  left join ultimo_movimento u on u.produto_id = p.id
                 where p.ativo and p.finalidade in ('VENDA', 'AMBOS')
                 group by p.id, p.nome, p.preco_venda
                 order by lower(p.nome)
                """)
                .query((rs, n) -> produto(rs, fuso, hoje))
                .list();
    }

    private static MotorDePreco.Produto produto(ResultSet rs, ZoneId fuso, LocalDate hoje)
            throws SQLException {
        OffsetDateTime referencia = rs.getObject("referencia", OffsetDateTime.class);
        Integer diasSemVenda = referencia == null ? null
                : (int) java.time.temporal.ChronoUnit.DAYS.between(
                        referencia.toInstant().atZone(fuso).toLocalDate(), hoje);
        BigDecimal custoMedio = rs.getBigDecimal("custo_medio");
        BigDecimal preco = rs.getBigDecimal("preco_venda");
        return new MotorDePreco.Produto(rs.getObject("produto_id", UUID.class),
                rs.getString("nome"),
                custoMedio == null ? null : new Money(custoMedio),
                preco == null ? null : new Money(preco),
                rs.getBigDecimal("quantidade"), diasSemVenda);
    }

    // ------------------------------------------------------------------ gravar e ler

    /**
     * Troca as sugestões abertas do salão pelas desta rodada.
     *
     * <p>Apagar tudo e reescrever, e não conciliar linha a linha: o que deixou de se aplicar precisa
     * sumir — um produto que voltou a vender não continua pedindo promoção —, e "as sugestões de
     * agora" é exatamente o resultado da rodada, não uma mistura dela com a anterior.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void substituir(List<Gravar> sugestoes, Instant agora) {
        UUID tenant = TenantContext.obrigatorio();
        jdbc.sql("delete from sugestao_de_preco").update();
        for (Gravar s : sugestoes) {
            jdbc.sql("""
                    insert into sugestao_de_preco (estabelecimento_id, produto_id, motivo,
                            preco_atual, custo_medio, preco_sugerido, margem_atual, margem_sugerida,
                            racional, explicacao, gerada_em)
                    values (:estabelecimento, :produto, :motivo, :precoAtual, :custo, :sugerido,
                            :margemAtual, :margemSugerida, :racional, :explicacao, :agora)
                    """)
                    .param("estabelecimento", tenant)
                    .param("produto", s.sugestao().produtoId())
                    .param("motivo", s.sugestao().motivo().name())
                    .param("precoAtual", s.sugestao().precoAtual() == null ? null
                            : s.sugestao().precoAtual().valor())
                    .param("custo", s.sugestao().custoMedio().valor())
                    .param("sugerido", s.sugestao().precoSugerido().valor())
                    .param("margemAtual", s.sugestao().margemAtual())
                    .param("margemSugerida", s.sugestao().margemSugerida())
                    .param("racional", s.sugestao().racional())
                    .param("explicacao", s.explicacao())
                    .param("agora", Timestamp.from(agora))
                    .update();
        }
    }

    public record Gravar(MotorDePreco.Sugestao sugestao, String explicacao) {
    }

    /** Uma sugestão como a tela a lê, já com o nome do produto. */
    public record Linha(UUID id, UUID produtoId, String produto, MotivoDaSugestao motivo,
                        BigDecimal precoAtual, BigDecimal custoMedio, BigDecimal precoSugerido,
                        BigDecimal margemAtual, BigDecimal margemSugerida, String racional,
                        String explicacao, Instant geradaEm) {
    }

    /**
     * As sugestões abertas, do motivo que mais dói para o que menos dói, e por valor.
     *
     * <p>Margem abaixo do mínimo primeiro: é dinheiro saindo hoje. Preço quebrado por último: é
     * acabamento.
     */
    @Transactional(readOnly = true)
    public List<Linha> abertas() {
        return jdbc.sql("""
                select s.id, s.produto_id, p.nome, s.motivo, s.preco_atual, s.custo_medio,
                       s.preco_sugerido, s.margem_atual, s.margem_sugerida, s.racional,
                       s.explicacao, s.gerada_em
                  from sugestao_de_preco s
                  join produto p on p.id = s.produto_id
                 order by case s.motivo
                            when 'MARGEM_ABAIXO_DO_MINIMO' then 1
                            when 'ENCALHADO' then 2
                            when 'MARGEM_ALTA' then 3
                            else 4 end,
                          s.custo_medio desc, lower(p.nome)
                """)
                .query((rs, n) -> new Linha(rs.getObject("id", UUID.class),
                        rs.getObject("produto_id", UUID.class), rs.getString("nome"),
                        MotivoDaSugestao.valueOf(rs.getString("motivo")),
                        rs.getBigDecimal("preco_atual"), rs.getBigDecimal("custo_medio"),
                        rs.getBigDecimal("preco_sugerido"), rs.getBigDecimal("margem_atual"),
                        rs.getBigDecimal("margem_sugerida"), rs.getString("racional"),
                        rs.getString("explicacao"),
                        rs.getObject("gerada_em", OffsetDateTime.class).toInstant()))
                .list();
    }
}
