package br.com.salao.estoque.internal.infra;

import br.com.salao.estoque.api.ItemDaFichaTecnica;
import br.com.salao.estoque.api.UnidadeDeMedida;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** RT-EST-007 — a ficha técnica dos serviços. */
public class FichasJdbc {

    private final JdbcClient jdbc;

    public FichasJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Define quanto o serviço consome do produto, substituindo se já houver.
     *
     * <p>Definir é substituir: mandar a mesma dupla de novo troca a quantidade em vez de criar uma
     * segunda linha. Duas linhas do mesmo produto fariam a baixa dobrar sem ninguém pedir — e o
     * índice único já impediria, com uma mensagem de constraint que não diz nada a quem chamou.
     */
    @Transactional
    public UUID definir(UUID estabelecimentoId, UUID servicoId, UUID produtoId,
                        BigDecimal quantidade, Instant quando, UUID quem) {
        return jdbc.sql("""
                insert into ficha_tecnica (estabelecimento_id, servico_id, produto_id,
                                           quantidade, criado_em, criado_por)
                values (:estabelecimento, :servico, :produto, :quantidade, :quando, :quem)
                on conflict (estabelecimento_id, servico_id, produto_id) do update
                   set quantidade = excluded.quantidade,
                       criado_em  = excluded.criado_em,
                       criado_por = excluded.criado_por
                returning id
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("servico", servicoId)
                .param("produto", produtoId)
                .param("quantidade", quantidade)
                .param("quando", OffsetDateTime.ofInstant(quando, ZoneOffset.UTC))
                .param("quem", quem)
                .query(UUID.class)
                .single();
    }

    @Transactional
    public int remover(UUID servicoId, UUID produtoId) {
        return jdbc.sql("""
                delete from ficha_tecnica
                 where servico_id = :servico and produto_id = :produto
                """)
                .param("servico", servicoId)
                .param("produto", produtoId)
                .update();
    }

    /** A ficha de um serviço, com o nome e a unidade do insumo — é o que a tela mostra. */
    @Transactional(readOnly = true)
    public List<ItemDaFichaTecnica> doServico(UUID servicoId) {
        return jdbc.sql("""
                select f.id, f.servico_id, f.produto_id, p.nome as produto,
                       p.unidade, f.quantidade
                  from ficha_tecnica f
                  join produto p on p.id = f.produto_id
                 where f.servico_id = :servico
                 order by p.nome
                """)
                .param("servico", servicoId)
                .query(FichasJdbc::mapear)
                .list();
    }

    private static ItemDaFichaTecnica mapear(ResultSet rs, int linha) throws SQLException {
        return new ItemDaFichaTecnica(
                rs.getObject("id", UUID.class),
                rs.getObject("servico_id", UUID.class),
                rs.getObject("produto_id", UUID.class),
                rs.getString("produto"),
                UnidadeDeMedida.valueOf(rs.getString("unidade")),
                rs.getBigDecimal("quantidade"));
    }
}
