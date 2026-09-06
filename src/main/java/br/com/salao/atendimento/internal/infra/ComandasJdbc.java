package br.com.salao.atendimento.internal.infra;

import br.com.salao.atendimento.api.ComandaResumo;
import br.com.salao.atendimento.api.OrigemDaComanda;
import br.com.salao.atendimento.api.StatusDaComanda;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
                rs.getBigDecimal("valor_bruto"),
                rs.getBigDecimal("valor_desconto"),
                rs.getBigDecimal("valor_liquido"),
                rs.getInt("versao"));
    }
}
