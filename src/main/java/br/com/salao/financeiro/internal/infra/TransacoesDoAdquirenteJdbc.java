package br.com.salao.financeiro.internal.infra;

import br.com.salao.financeiro.internal.domain.SituacaoDaTransacao;
import br.com.salao.financeiro.internal.domain.TransacaoLida;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-009 — as vendas que a adquirente descreveu.
 *
 * <p>Tudo {@code MANDATORY}: a importação inteira é uma transação — as linhas, a importação com as
 * contagens e o mapeamento confirmado entram juntos ou não entram.
 */
public class TransacoesDoAdquirenteJdbc {

    private final JdbcClient jdbc;

    public TransacoesDoAdquirenteJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Existente(UUID id, BigDecimal valorBruto, SituacaoDaTransacao situacao) {
    }

    /**
     * @return o id, se entrou agora; vazio se o Stone ID já existia — inclusive por uma importação
     *         simultânea, que disputou a mesma linha
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> inserir(String adquirente, TransacaoLida venda, Instant capturadaEm,
                                  UUID importacaoId, Instant agora) {
        boolean cancelada = venda.situacao() == SituacaoDaTransacao.CANCELADA;
        return jdbc.sql("""
                insert into transacao_do_adquirente
                    (estabelecimento_id, adquirente, id_na_adquirente, nsu, autorizacao,
                     capturada_em, hora_informada, produto, bandeira, parcelas, valor_bruto,
                     valor_liquido, situacao, cancelada_em, primeira_importacao_id,
                     ultima_importacao_id, criada_em)
                values (:estabelecimento, :adquirente, :id, :nsu, :autorizacao, :capturadaEm,
                        :horaInformada, :produto, :bandeira, :parcelas, :bruto, :liquido,
                        :situacao, :canceladaEm, :importacao, :importacao, :agora)
                on conflict (estabelecimento_id, adquirente, id_na_adquirente) do nothing
                returning id
                """)
                .param("estabelecimento", TenantContext.obrigatorio())
                .param("adquirente", adquirente)
                .param("id", venda.idNaAdquirente())
                .param("nsu", venda.nsu())
                .param("autorizacao", venda.autorizacao())
                .param("capturadaEm", Timestamp.from(capturadaEm))
                .param("horaInformada", venda.horaInformada())
                .param("produto", venda.produto() == null ? null : venda.produto().name())
                .param("bandeira", venda.bandeira())
                .param("parcelas", venda.parcelas())
                .param("bruto", venda.valorBruto())
                .param("liquido", venda.valorLiquido())
                .param("situacao", venda.situacao().name())
                .param("canceladaEm", cancelada ? Timestamp.from(agora) : null)
                .param("importacao", importacaoId)
                .param("agora", Timestamp.from(agora))
                .query(UUID.class)
                .optional();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Existente existente(String adquirente, String idNaAdquirente) {
        return jdbc.sql("""
                select id, valor_bruto, situacao from transacao_do_adquirente
                 where adquirente = :adquirente and id_na_adquirente = :id
                """)
                .param("adquirente", adquirente)
                .param("id", idNaAdquirente)
                .query((rs, n) -> new Existente(rs.getObject("id", UUID.class),
                        rs.getBigDecimal("valor_bruto"),
                        SituacaoDaTransacao.valueOf(rs.getString("situacao"))))
                .single();
    }

    /** Aprovada vira cancelada. Só nessa direção — o {@code where} garante mesmo sob disputa. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean cancelar(UUID id, UUID importacaoId, Instant agora) {
        return jdbc.sql("""
                update transacao_do_adquirente
                   set situacao = 'CANCELADA', cancelada_em = :agora,
                       ultima_importacao_id = :importacao
                 where id = :id and situacao = 'APROVADA'
                """)
                .param("id", id)
                .param("importacao", importacaoId)
                .param("agora", Timestamp.from(agora))
                .update() == 1;
    }
}
