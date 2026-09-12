package br.com.salao.estoque.internal.infra;

import br.com.salao.estoque.api.InventarioDeEstoque;
import br.com.salao.estoque.api.StatusDoInventario;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EST-006 — o inventário e suas contagens.
 *
 * <p>Aceita {@code update} — é documento de trabalho, e recontar uma prateleira corrige a linha.
 * O que ele PRODUZ, o movimento de estoque, é imutável (RN-EST-015); a separação é o que permite
 * corrigir a contagem sem poder corrigir o movimento que saiu dela.
 */
public class InventariosJdbc {

    private final JdbcClient jdbc;

    public InventariosJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public UUID abrir(UUID estabelecimentoId, String observacao, Instant quando, UUID quem) {
        return jdbc.sql("""
                insert into inventario (estabelecimento_id, observacao, criado_em, criado_por)
                values (:estabelecimento, :observacao, :quando, :quem)
                returning id
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("observacao", observacao)
                .param("quando", OffsetDateTime.ofInstant(quando, ZoneOffset.UTC))
                .param("quem", quem)
                .query(UUID.class)
                .single();
    }

    @Transactional(readOnly = true)
    public Optional<StatusDoInventario> statusDe(UUID inventarioId) {
        return jdbc.sql("select status from inventario where id = :id")
                .param("id", inventarioId)
                .query(String.class)
                .optional()
                .map(StatusDoInventario::valueOf);
    }

    /**
     * Grava a contagem de um lote, substituindo a anterior se houver.
     *
     * <p>Contar o mesmo lote de novo é <strong>recontar</strong>, não somar — e a segunda contagem
     * traz o esperado do seu próprio instante, que é o que mantém o ajuste sendo um delta honesto.
     */
    @Transactional
    public void contar(UUID estabelecimentoId, UUID inventarioId, UUID loteId, UUID produtoId,
                       BigDecimal esperada, BigDecimal contada, String observacao,
                       Instant quando, UUID quem) {
        jdbc.sql("""
                insert into inventario_item (estabelecimento_id, inventario_id, lote_id,
                                             produto_id, quantidade_esperada, quantidade_contada,
                                             observacao, contado_em, contado_por)
                values (:estabelecimento, :inventario, :lote, :produto, :esperada, :contada,
                        :observacao, :quando, :quem)
                on conflict (inventario_id, lote_id) do update
                   set quantidade_esperada = excluded.quantidade_esperada,
                       quantidade_contada  = excluded.quantidade_contada,
                       observacao          = excluded.observacao,
                       contado_em          = excluded.contado_em,
                       contado_por         = excluded.contado_por
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("inventario", inventarioId)
                .param("lote", loteId)
                .param("produto", produtoId)
                .param("esperada", esperada)
                .param("contada", contada)
                .param("observacao", observacao)
                .param("quando", OffsetDateTime.ofInstant(quando, ZoneOffset.UTC))
                .param("quem", quem)
                .update();
    }

    /**
     * Encerra o inventário. Idempotente no próprio SQL.
     *
     * <p>{@code where status = 'ABERTO'} em vez de conferir antes e escrever depois: sem isso,
     * duas requisições simultâneas de "aplicar" passariam as duas pela conferência e aplicariam o
     * ajuste em dobro.
     *
     * @return 1 quando encerrou agora, 0 quando já estava encerrado
     */
    @Transactional
    public int encerrar(UUID inventarioId, StatusDoInventario destino, Instant quando, UUID quem) {
        return jdbc.sql("""
                update inventario
                   set status = :destino, encerrado_em = :quando, encerrado_por = :quem
                 where id = :id and status = 'ABERTO'
                """)
                .param("destino", destino.name())
                .param("quando", OffsetDateTime.ofInstant(quando, ZoneOffset.UTC))
                .param("quem", quem)
                .param("id", inventarioId)
                .update();
    }

    /** O inventário com os itens e o nome do produto e do lote, para a tela de conferência. */
    @Transactional(readOnly = true)
    public Optional<InventarioDeEstoque> porId(UUID inventarioId) {
        var cabeca = jdbc.sql("""
                select id, status, observacao, criado_em, encerrado_em
                  from inventario where id = :id
                """)
                .param("id", inventarioId)
                .query((ResultSet rs, int linha) -> new Object[] {
                        rs.getObject("id", UUID.class),
                        StatusDoInventario.valueOf(rs.getString("status")),
                        rs.getString("observacao"),
                        rs.getObject("criado_em", OffsetDateTime.class).toInstant(),
                        Optional.ofNullable(rs.getObject("encerrado_em", OffsetDateTime.class))
                                .map(OffsetDateTime::toInstant).orElse(null) })
                .optional();
        if (cabeca.isEmpty()) {
            return Optional.empty();
        }
        Object[] dados = cabeca.get();
        return Optional.of(new InventarioDeEstoque((UUID) dados[0],
                (StatusDoInventario) dados[1], (String) dados[2], (Instant) dados[3],
                (Instant) dados[4], itensDe(inventarioId)));
    }

    @Transactional(readOnly = true)
    public List<InventarioDeEstoque.ItemContado> itensDe(UUID inventarioId) {
        return jdbc.sql("""
                select i.lote_id, i.produto_id, p.nome as produto, l.codigo as lote_codigo,
                       i.quantidade_esperada, i.quantidade_contada, i.observacao, i.contado_em
                  from inventario_item i
                  join produto p on p.id = i.produto_id
                  join lote l on l.id = i.lote_id
                 where i.inventario_id = :id
                 order by p.nome, l.validade nulls last, l.recebido_em, i.lote_id
                """)
                .param("id", inventarioId)
                /*
                 * `RowMapper` + `list()`, e não um lambda que acumula numa lista.
                 *
                 * A primeira versão era `query((ResultSet rs) -> itens.add(...))`, e o Java
                 * resolveu aquilo como `ResultSetExtractor` — que é chamado UMA vez, com o
                 * cursor antes da primeira linha. O erro foi "ResultSet não está posicionado
                 * corretamente, talvez você precise chamar next", em runtime.
                 *
                 * A causa é sutil: `List.add` devolve `boolean`, então o lambda de corpo-expressão
                 * não é void-compatível e deixa de casar com `RowCallbackHandler`. Um bloco
                 * `{ itens.add(...); }` funcionaria — e é justamente o tipo de diferença que
                 * ninguém percebe lendo. `RowMapper` não tem essa ambiguidade.
                 */
                .query(InventariosJdbc::mapearItem)
                .list();
    }

    private static InventarioDeEstoque.ItemContado mapearItem(ResultSet rs, int linha)
            throws SQLException {
        return new InventarioDeEstoque.ItemContado(
                rs.getObject("lote_id", UUID.class),
                rs.getObject("produto_id", UUID.class),
                rs.getString("produto"),
                rs.getString("lote_codigo"),
                rs.getBigDecimal("quantidade_esperada"),
                rs.getBigDecimal("quantidade_contada"),
                rs.getString("observacao"),
                rs.getObject("contado_em", OffsetDateTime.class).toInstant());
    }

    /**
     * Trava o inventário para aplicar.
     *
     * <p>{@code MANDATORY} pela lição de RT-FIN-006: um lock vive pela transação, e fora de uma
     * ele seria adquirido e devolvido no mesmo instante — uma trava que silenciosamente não trava.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void travar(UUID inventarioId) {
        jdbc.sql("select id from inventario where id = :id for update")
                .param("id", inventarioId)
                .query(UUID.class)
                .optional();
    }
}
