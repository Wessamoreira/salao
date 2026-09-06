package br.com.salao.atendimento.internal.infra;

import br.com.salao.atendimento.api.ComandaResumo;
import br.com.salao.atendimento.api.ExecutanteDoItem;
import br.com.salao.atendimento.api.ItemDaComanda;
import br.com.salao.atendimento.api.OrigemDaComissao;
import br.com.salao.atendimento.api.PapelDoExecutante;
import br.com.salao.atendimento.api.TipoDeItem;
import br.com.salao.atendimento.api.OrigemDaComanda;
import br.com.salao.atendimento.api.StatusDaComanda;
import br.com.salao.shared.dinheiro.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** RT-ATD-001 — as comandas. */
public class ComandasJdbc {

    private static final String CAMPOS = """
            id, cliente_id, numero, status, origem, aberta_em, aberta_por, fechada_em,
            valor_bruto, valor_desconto, valor_liquido, versao
            """;

    private final JdbcClient jdbc;

    public ComandasJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * O próximo número visível do salão, num comando só.
     *
     * <p>O {@code on conflict do update} é o que torna isto atômico: a primeira comanda do salão
     * cria a linha do contador, e todas as outras a incrementam sob o lock da própria linha. Ler
     * e depois escrever, em dois passos, é a versão que duas recepcionistas simultâneas quebram.
     *
     * <p>Como o incremento vive na transação da abertura, um rollback devolve o número — é o que
     * mantém a numeração sem buracos.
     */
    @Transactional
    public long proximoNumero(UUID estabelecimentoId) {
        return jdbc.sql("""
                insert into contador_de_comanda (estabelecimento_id, proximo)
                values (:estabelecimento, 1)
                on conflict (estabelecimento_id)
                  do update set proximo = contador_de_comanda.proximo + 1
                returning proximo
                """)
                .param("estabelecimento", estabelecimentoId)
                .query(Long.class)
                .single();
    }

    /**
     * Insere a comanda, ou não insere nada se o cliente já tem uma aberta.
     *
     * <p>{@code on conflict} em vez de conferir antes: entre o {@code select} e o {@code insert}
     * cabe outra transação, e é justamente na hora do movimento — duas pessoas abrindo a comanda da
     * mesma cliente ao mesmo tempo — que ela caberia. O índice parcial é quem decide, e o vazio
     * devolvido aqui significa "a outra chegou primeiro".
     *
     * <p>O conflito é <strong>apontado</strong>, não genérico: um {@code do nothing} solto engoliria
     * também a colisão de {@code numero}, que não é desfecho normal nenhum — é o contador furado, e
     * precisa estourar para alguém ver.
     */
    @Transactional
    public Optional<UUID> inserirSeNaoHouverAberta(UUID estabelecimentoId, UUID clienteId,
                                                   long numero, OrigemDaComanda origem,
                                                   Instant abertaEm, UUID abertaPor) {
        return jdbc.sql("""
                insert into comanda (estabelecimento_id, cliente_id, numero, origem,
                                     aberta_em, aberta_por)
                values (:estabelecimento, :cliente, :numero, :origem, :abertaEm, :abertaPor)
                on conflict (estabelecimento_id, cliente_id) where status = 'ABERTA'
                  do nothing
                returning id
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("cliente", clienteId)
                .param("numero", numero)
                .param("origem", origem.name())
                .param("abertaEm", OffsetDateTime.ofInstant(abertaEm, ZoneOffset.UTC))
                .param("abertaPor", abertaPor)
                .query(UUID.class)
                .optional();
    }

    @Transactional(readOnly = true)
    public Optional<ComandaResumo> porId(UUID id) {
        return jdbc.sql("select " + CAMPOS + " from comanda where id = :id")
                .param("id", id)
                .query(ComandasJdbc::mapear)
                .optional();
    }

    @Transactional(readOnly = true)
    public Optional<ComandaResumo> abertaDoCliente(UUID clienteId) {
        return jdbc.sql("select " + CAMPOS + """
                  from comanda where cliente_id = :cliente and status = 'ABERTA'
                """)
                .param("cliente", clienteId)
                .query(ComandasJdbc::mapear)
                .optional();
    }

    /** A barra do balcão (R-UX-07): as mais antigas primeiro, que são as esquecidas. */
    @Transactional(readOnly = true)
    public List<ComandaResumo> abertas() {
        return jdbc.sql("select " + CAMPOS + """
                  from comanda where status = 'ABERTA' order by aberta_em
                """)
                .query(ComandasJdbc::mapear)
                .list();
    }

    // ------------------------------------------------------------------ RT-ATD-002

    private static final String CAMPOS_DO_ITEM = """
            id, tipo, servico_id, produto_id, agendamento_id, descricao, quantidade,
            preco_unitario, desconto, valor_total
            """;

    @Transactional
    public UUID inserirItemDeServico(UUID estabelecimentoId, UUID comandaId, UUID servicoId,
                                     UUID agendamentoId, String descricao, BigDecimal quantidade,
                                     Money precoUnitario, Money valorTotal, Instant criadoEm) {
        return jdbc.sql("""
                insert into item_comanda (estabelecimento_id, comanda_id, tipo, servico_id,
                                          agendamento_id, descricao, quantidade, preco_unitario,
                                          valor_total, criado_em)
                values (:estabelecimento, :comanda, 'SERVICO', :servico, :agendamento, :descricao,
                        :quantidade, :preco, :total, :criadoEm)
                returning id
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("comanda", comandaId)
                .param("servico", servicoId)
                .param("agendamento", agendamentoId)
                .param("descricao", descricao)
                .param("quantidade", quantidade)
                .param("preco", precoUnitario.valor())
                .param("total", valorTotal.valor())
                .param("criadoEm", OffsetDateTime.ofInstant(criadoEm, ZoneOffset.UTC))
                .query(UUID.class)
                .single();
    }

    /**
     * Insere o executante com base e comissão zeradas; quem as preenche é
     * {@link #recalcularComissoesDoItem}, sempre.
     *
     * <p>Zerar e recalcular parece um passo a mais, e é de propósito: a base depende de
     * {@code descontoAfetaComissao}, e essa fórmula já vive no SQL do recálculo. Calculá-la
     * também aqui seria a mesma conta em dois lugares — que é o defeito que RN-ATD-008 existe
     * para impedir, uma camada acima.
     */
    @Transactional
    public void inserirExecutante(UUID estabelecimentoId, UUID itemId, UUID profissionalId,
                                  PapelDoExecutante papel, BigDecimal percentual,
                                  OrigemDaComissao origem) {
        jdbc.sql("""
                insert into executante (estabelecimento_id, item_comanda_id, profissional_id,
                                        papel, percentual_comissao, regra_origem,
                                        base_calculo, valor_comissao)
                values (:estabelecimento, :item, :profissional, :papel, :percentual, :origem, 0, 0)
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("item", itemId)
                .param("profissional", profissionalId)
                .param("papel", papel.name())
                .param("percentual", percentual)
                .param("origem", origem.name())
                .update();
    }

    @Transactional(readOnly = true)
    public Optional<UUID> itemDoExecutante(UUID executanteId) {
        return jdbc.sql("select item_comanda_id from executante where id = :id")
                .param("id", executanteId)
                .query(UUID.class)
                .optional();
    }

    @Transactional
    public int removerExecutante(UUID executanteId) {
        return jdbc.sql("delete from executante where id = :id")
                .param("id", executanteId).update();
    }

    /**
     * Apaga o item. Os executantes vão junto, por {@code on delete cascade}.
     *
     * <p>Apagar, e não marcar como removido: item lançado por engano não é fato do atendimento, é
     * digitação. O que precisa sobreviver a uma correção é o <em>fechamento</em> — e comanda
     * fechada não aceita remoção (RN-ATD-006).
     */
    @Transactional
    public int removerItem(UUID itemId) {
        return jdbc.sql("delete from item_comanda where id = :id").param("id", itemId).update();
    }

    @Transactional(readOnly = true)
    public Optional<UUID> comandaDoItem(UUID itemId) {
        return jdbc.sql("select comanda_id from item_comanda where id = :id")
                .param("id", itemId)
                .query(UUID.class)
                .optional();
    }

    /**
     * Recalcula os totais gravados da comanda a partir dos itens.
     *
     * <p>Uma consulta só, no banco: somar na aplicação exigiria carregar todos os itens para
     * escrever três números, e duas somas — a da tela e a da gravação — divergem no dia em que
     * alguém arredondar diferente.
     *
     * <p>{@code versao} sobe junto: quem estava com a comanda na tela precisa saber que ela mudou.
     */
    @Transactional
    public void recalcularTotais(UUID comandaId) {
        jdbc.sql("""
                update comanda c
                   set valor_bruto = coalesce(t.bruto, 0),
                       valor_desconto = coalesce(t.desconto, 0),
                       valor_liquido = coalesce(t.bruto, 0) - coalesce(t.desconto, 0),
                       versao = c.versao + 1
                  from (select sum(preco_unitario * quantidade) as bruto,
                               sum(desconto) as desconto
                          from item_comanda where comanda_id = :comanda) t
                 where c.id = :comanda
                """)
                .param("comanda", comandaId)
                .update();
    }

    /** Os itens da comanda, com os executantes de cada um, em duas consultas. */
    @Transactional(readOnly = true)
    public List<ItemDaComanda> itensDe(UUID comandaId) {
        record Linha(UUID id, TipoDeItem tipo, UUID servicoId, UUID produtoId, UUID agendamentoId,
                     String descricao, BigDecimal quantidade, Money preco, Money desconto,
                     Money total) {
        }
        List<Linha> linhas = jdbc.sql("select " + CAMPOS_DO_ITEM + """
                  from item_comanda where comanda_id = :comanda order by criado_em, id
                """)
                .param("comanda", comandaId)
                .query((rs, i) -> new Linha(
                        rs.getObject("id", UUID.class),
                        TipoDeItem.valueOf(rs.getString("tipo")),
                        rs.getObject("servico_id", UUID.class),
                        rs.getObject("produto_id", UUID.class),
                        rs.getObject("agendamento_id", UUID.class),
                        rs.getString("descricao"),
                        rs.getBigDecimal("quantidade"),
                        new Money(rs.getBigDecimal("preco_unitario")),
                        new Money(rs.getBigDecimal("desconto")),
                        new Money(rs.getBigDecimal("valor_total"))))
                .list();
        if (linhas.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<ExecutanteDoItem>> porItem = executantesDe(
                linhas.stream().map(Linha::id).toList());
        List<ItemDaComanda> itens = new ArrayList<>(linhas.size());
        for (Linha l : linhas) {
            itens.add(new ItemDaComanda(l.id(), l.tipo(), l.servicoId(), l.produtoId(),
                    l.agendamentoId(), l.descricao(), l.quantidade(), l.preco(), l.desconto(),
                    l.total(), porItem.getOrDefault(l.id(), List.of())));
        }
        return itens;
    }

    /**
     * Os executantes de vários itens, numa consulta.
     *
     * <p>Um por item seria uma consulta por linha para desenhar a comanda — a mesma razão de
     * {@code ClienteApi.nomesDe} existir.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<ExecutanteDoItem>> executantesDe(List<UUID> itens) {
        Map<UUID, List<ExecutanteDoItem>> porItem = new LinkedHashMap<>();
        jdbc.sql("""
                select id, item_comanda_id, profissional_id, papel, percentual_comissao,
                       regra_origem, base_calculo, valor_comissao
                  from executante where item_comanda_id = any(:itens) order by papel, id
                """)
                .param("itens", itens.toArray(UUID[]::new))
                .query((rs, i) -> {
                    UUID item = rs.getObject("item_comanda_id", UUID.class);
                    porItem.computeIfAbsent(item, k -> new ArrayList<>()).add(new ExecutanteDoItem(
                            rs.getObject("id", UUID.class),
                            rs.getObject("profissional_id", UUID.class),
                            PapelDoExecutante.valueOf(rs.getString("papel")),
                            rs.getBigDecimal("percentual_comissao"),
                            OrigemDaComissao.valueOf(rs.getString("regra_origem")),
                            new Money(rs.getBigDecimal("base_calculo")),
                            new Money(rs.getBigDecimal("valor_comissao"))));
                    return item;
                })
                .list();
        return porItem;
    }

    // ------------------------------------------------------------------ RT-ATD-004

    /**
     * Grava o desconto e refaz o total da linha na mesma frase.
     *
     * <p>{@code valor_total} é derivado de {@code preco_unitario * quantidade - desconto}, e
     * calculá-lo aqui — e não na aplicação — é o que impede as duas contas de divergirem no dia em
     * que alguém arredondar diferente. É a mesma razão de {@link #recalcularTotais}.
     */
    @Transactional
    public void aplicarDescontoNoItem(UUID itemId, Money desconto) {
        jdbc.sql("""
                update item_comanda
                   set desconto = :desconto,
                       valor_total = preco_unitario * quantidade - :desconto
                 where id = :id
                """)
                .param("id", itemId)
                .param("desconto", desconto.valor())
                .update();
    }

    /**
     * Refaz a base e o valor da comissão de todos os executantes do item.
     *
     * <p>{@code descontoAfetaComissao} é configuração do salão (pergunta 2 de
     * {@code 13-perguntas-em-aberto}), e é ela que decide sobre qual valor o percentual incide:
     * o total já descontado, ou o valor cheio do serviço. A diferença é quem paga o desconto — o
     * salão ou o profissional —, e é decisão de negócio, não de código.
     *
     * <p>O percentual não entra na conta vindo da ficha: vem da própria linha, que é o snapshot
     * gravado no lançamento (RN-ATD-010). Reler a ficha aqui apagaria o snapshot em silêncio.
     */
    @Transactional
    public void recalcularComissoesDoItem(UUID itemId, boolean descontoAfetaComissao) {
        jdbc.sql("""
                update executante e
                   set base_calculo = b.base,
                       valor_comissao = b.base * e.percentual_comissao / 100
                  from (select case when :afeta then i.valor_total
                                    else i.preco_unitario * i.quantidade end as base
                          from item_comanda i where i.id = :item) b
                 where e.item_comanda_id = :item
                """)
                .param("item", itemId)
                .param("afeta", descontoAfetaComissao)
                .update();
    }

    private static ComandaResumo mapear(ResultSet rs, int linha) throws SQLException {
        OffsetDateTime fechada = rs.getObject("fechada_em", OffsetDateTime.class);
        return new ComandaResumo(
                rs.getObject("id", UUID.class),
                rs.getObject("cliente_id", UUID.class),
                rs.getLong("numero"),
                StatusDaComanda.valueOf(rs.getString("status")),
                OrigemDaComanda.valueOf(rs.getString("origem")),
                rs.getObject("aberta_em", OffsetDateTime.class).toInstant(),
                rs.getObject("aberta_por", UUID.class),
                fechada == null ? null : fechada.toInstant(),
                new Money(rs.getBigDecimal("valor_bruto")),
                new Money(rs.getBigDecimal("valor_desconto")),
                new Money(rs.getBigDecimal("valor_liquido")),
                rs.getInt("versao"));
    }
}
