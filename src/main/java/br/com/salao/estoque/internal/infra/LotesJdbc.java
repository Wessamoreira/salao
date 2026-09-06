package br.com.salao.estoque.internal.infra;

import br.com.salao.estoque.api.LoteResumo;
import br.com.salao.estoque.api.SaldoDoProduto;
import br.com.salao.estoque.api.UnidadeDeMedida;
import br.com.salao.estoque.api.TipoDeMovimento;
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

/** RT-EST-002 — lotes e movimentos. */
public class LotesJdbc {

    /**
     * O saldo vem sempre junto, como projeção.
     *
     * <p>Não existe coluna de saldo para alguém atualizar — e por isso não existe saldo errado que
     * precise de script para consertar. O preço é um {@code join} agregado em toda leitura de
     * lote, que é barato: um salão tem dezenas de lotes por produto, não milhões.
     */
    private static final String CAMPOS = """
            l.id, l.produto_id, l.codigo, l.custo_unitario, l.validade, l.recebido_em,
            coalesce((select sum(m.quantidade) from movimento_estoque m
                       where m.lote_id = l.id), 0) as quantidade
            """;

    private final JdbcClient jdbc;

    public LotesJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public UUID inserirLote(UUID estabelecimentoId, UUID produtoId, String codigo,
                            Money custoUnitario, LocalDate validade, LocalDate recebidoEm,
                            Instant criadoEm, UUID criadoPor) {
        return jdbc.sql("""
                insert into lote (estabelecimento_id, produto_id, codigo, custo_unitario,
                                  validade, recebido_em, criado_em, criado_por)
                values (:estabelecimento, :produto, :codigo, :custo, :validade, :recebido,
                        :criadoEm, :por)
                returning id
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("produto", produtoId)
                .param("codigo", codigo)
                .param("custo", custoUnitario.valor())
                .param("validade", validade == null ? null : Date.valueOf(validade))
                .param("recebido", Date.valueOf(recebidoEm))
                .param("criadoEm", OffsetDateTime.ofInstant(criadoEm, ZoneOffset.UTC))
                .param("por", criadoPor)
                .query(UUID.class)
                .single();
    }

    /**
     * Grava o movimento. O sinal da quantidade é que diz se entra ou sai.
     *
     * <p>Não há {@code update} nem {@code delete} nesta tabela em lugar nenhum do código: correção
     * é movimento novo, de sinal contrário (RT-EST-005). É a mesma disciplina do razão.
     */
    @Transactional
    public UUID inserirMovimento(UUID estabelecimentoId, UUID produtoId, UUID loteId,
                                 TipoDeMovimento tipo, BigDecimal quantidade,
                                 Money custoUnitario, String origemTipo, UUID origemId,
                                 String observacao, Instant criadoEm, UUID criadoPor) {
        return jdbc.sql("""
                insert into movimento_estoque (estabelecimento_id, produto_id, lote_id, tipo,
                                               quantidade, custo_unitario, origem_tipo, origem_id,
                                               observacao, criado_em, criado_por)
                values (:estabelecimento, :produto, :lote, :tipo, :quantidade, :custo,
                        :origemTipo, :origemId, :observacao, :criadoEm, :por)
                returning id
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("produto", produtoId)
                .param("lote", loteId)
                .param("tipo", tipo.name())
                .param("quantidade", quantidade)
                .param("custo", custoUnitario.valor())
                .param("origemTipo", origemTipo)
                .param("origemId", origemId)
                .param("observacao", observacao)
                .param("criadoEm", OffsetDateTime.ofInstant(criadoEm, ZoneOffset.UTC))
                .param("por", criadoPor)
                .query(UUID.class)
                .single();
    }

    @Transactional(readOnly = true)
    public Optional<LoteResumo> porId(UUID id) {
        return jdbc.sql("select " + CAMPOS + " from lote l where l.id = :id")
                .param("id", id)
                .query(LotesJdbc::mapear)
                .optional();
    }

    /**
     * Os lotes de um produto, <strong>na ordem do FEFO</strong>.
     *
     * <p>Vence primeiro, sai primeiro; o que não tem validade vai para o fim; empate desempata por
     * quem chegou antes. É esta ordem que RT-EST-004 vai consumir — e devolvê-la já ordenada evita
     * que cada chamador reinvente o critério, que é como metade das implementações de FEFO erra.
     */
    @Transactional(readOnly = true)
    public List<LoteResumo> doProduto(UUID produtoId, boolean somenteComSaldo) {
        return jdbc.sql("select " + CAMPOS + """
                  from lote l
                 where l.produto_id = :produto
                   and (:somenteComSaldo = false
                        or coalesce((select sum(m.quantidade) from movimento_estoque m
                                      where m.lote_id = l.id), 0) > 0)
                 order by l.validade nulls last, l.recebido_em, l.id
                """)
                .param("produto", produtoId)
                .param("somenteComSaldo", somenteComSaldo)
                .query(LotesJdbc::mapear)
                .list();
    }

    // ------------------------------------------------------------------ RT-EST-003

    /**
     * O saldo e o custo médio, calculados no banco.
     *
     * <h2>Por que só os lotes com saldo positivo entram na média</h2>
     *
     * <p>Lote zerado não tem unidade nenhuma para ponderar — incluí-lo puxaria a média para o custo
     * de mercadoria que já saiu. E lote com saldo negativo (que só aparece se um ajuste errar) tem
     * peso negativo: ele <em>subtrairia</em> valor de um estoque que existe, e a média poderia até
     * ficar negativa.
     *
     * <p>A quantidade, essa soma tudo — inclusive o negativo. Esconder um saldo negativo seria
     * esconder o erro que precisa ser corrigido (RT-EST-005).
     *
     * <h2>Nulo quando não há estoque</h2>
     *
     * <p>Zero significaria "tenho unidades que não custaram nada", que é o caso real da amostra do
     * fornecedor. Sem estoque não existe média a tirar, e o {@code case} devolve nulo.
     */
    private static final String SALDO = """
            with saldo_por_lote as (
              select l.produto_id,
                     l.custo_unitario,
                     coalesce((select sum(m.quantidade) from movimento_estoque m
                                where m.lote_id = l.id), 0) as saldo
                from lote l
            )
            select p.id as produto_id, p.nome, p.unidade,
                   coalesce(sum(s.saldo), 0) as quantidade,
                   case when sum(case when s.saldo > 0 then s.saldo end) > 0
                        then sum(case when s.saldo > 0 then s.saldo * s.custo_unitario end)
                           / sum(case when s.saldo > 0 then s.saldo end)
                   end as custo_medio
              from produto p
              left join saldo_por_lote s on s.produto_id = p.id
            """;

    @Transactional(readOnly = true)
    public Optional<SaldoDoProduto> saldoDoProduto(UUID produtoId) {
        return jdbc.sql(SALDO + " where p.id = :produto group by p.id, p.nome, p.unidade")
                .param("produto", produtoId)
                .query(LotesJdbc::mapearSaldo)
                .optional();
    }

    /**
     * O saldo de todos os produtos, em ordem de nome.
     *
     * <p>Uma consulta para a tela inteira. Perguntar produto a produto seria uma consulta por linha
     * para desenhar o inventário — a mesma razão de {@code ClienteApi.nomesDe}.
     */
    @Transactional(readOnly = true)
    public List<SaldoDoProduto> saldos(boolean somenteComEstoque) {
        return jdbc.sql(SALDO + """
                 where p.ativo
                 group by p.id, p.nome, p.unidade
                having :somenteComEstoque = false or coalesce(sum(s.saldo), 0) > 0
                 order by lower(p.nome)
                """)
                .param("somenteComEstoque", somenteComEstoque)
                .query(LotesJdbc::mapearSaldo)
                .list();
    }

    private static SaldoDoProduto mapearSaldo(ResultSet rs, int linha) throws SQLException {
        BigDecimal quantidade = rs.getBigDecimal("quantidade");
        BigDecimal custoMedio = rs.getBigDecimal("custo_medio");
        Money medio = custoMedio == null ? null : new Money(custoMedio);
        Money valor = medio == null || quantidade.signum() <= 0
                ? Money.ZERO
                : medio.multiplicar(quantidade);
        return new SaldoDoProduto(
                rs.getObject("produto_id", UUID.class),
                rs.getString("nome"),
                UnidadeDeMedida.valueOf(rs.getString("unidade")),
                quantidade,
                medio,
                valor);
    }

    private static LoteResumo mapear(ResultSet rs, int linha) throws SQLException {
        Date validade = rs.getDate("validade");
        return new LoteResumo(
                rs.getObject("id", UUID.class),
                rs.getObject("produto_id", UUID.class),
                rs.getString("codigo"),
                new Money(rs.getBigDecimal("custo_unitario")),
                rs.getBigDecimal("quantidade"),
                validade == null ? null : validade.toLocalDate(),
                rs.getDate("recebido_em").toLocalDate());
    }
}
