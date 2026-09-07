package br.com.salao.financeiro.internal.infra;

import br.com.salao.financeiro.api.EscopoDaRegra;
import br.com.salao.financeiro.api.RegraDeComissao;
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
import org.springframework.transaction.annotation.Transactional;

/** RT-FIN-002 — as regras de comissão. */
public class RegrasDeComissaoJdbc {

    private static final String CAMPOS = "id, escopo, profissional_id, servico_id, percentual";

    private final JdbcClient jdbc;

    public RegrasDeComissaoJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * As regras que alcançam este profissional neste serviço, <strong>da mais específica para a
     * mais geral</strong>.
     *
     * <p>Uma consulta só, ordenada no banco. Buscar nível a nível seriam três idas ao banco para
     * responder uma pergunta — e a ordem da cascata acabaria escrita no Java, longe da tabela que
     * a define.
     *
     * <p>O {@code order by} usa a mesma ordem de especificidade de {@code EscopoDaRegra.peso}: o
     * par vence o serviço, que vence o salão. Escrevê-la aqui em texto e lá em número é o tipo de
     * duplicação que diverge — por isso o teste compara as duas.
     */
    @Transactional(readOnly = true)
    public List<RegraDeComissao> candidatas(UUID profissionalId, UUID servicoId) {
        return jdbc.sql("select " + CAMPOS + """
                  from regra_comissao
                 where (escopo = 'SALAO')
                    or (escopo = 'SERVICO' and servico_id = :servico)
                    or (escopo = 'PROFISSIONAL_SERVICO'
                        and servico_id = :servico and profissional_id = :profissional)
                 order by case escopo
                            when 'PROFISSIONAL_SERVICO' then 3
                            when 'SERVICO' then 2
                            else 1
                          end desc
                """)
                .param("servico", servicoId)
                .param("profissional", profissionalId)
                .query(RegrasDeComissaoJdbc::mapear)
                .list();
    }

    @Transactional
    public UUID salvar(UUID estabelecimentoId, EscopoDaRegra escopo, UUID profissionalId,
                       UUID servicoId, BigDecimal percentual, Instant criadoEm, UUID criadoPor) {
        return jdbc.sql("""
                insert into regra_comissao (estabelecimento_id, escopo, profissional_id,
                                            servico_id, percentual, criado_em, criado_por)
                values (:estabelecimento, :escopo, :profissional, :servico, :percentual,
                        :criadoEm, :por)
                on conflict (estabelecimento_id, escopo, profissional_id, servico_id)
                  do update set percentual = excluded.percentual,
                                criado_em = excluded.criado_em,
                                criado_por = excluded.criado_por
                returning id
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("escopo", escopo.name())
                .param("profissional", profissionalId)
                .param("servico", servicoId)
                .param("percentual", percentual)
                .param("criadoEm", OffsetDateTime.ofInstant(criadoEm, ZoneOffset.UTC))
                .param("por", criadoPor)
                .query(UUID.class)
                .single();
    }

    @Transactional
    public int remover(UUID id) {
        return jdbc.sql("delete from regra_comissao where id = :id").param("id", id).update();
    }

    @Transactional(readOnly = true)
    public Optional<RegraDeComissao> porId(UUID id) {
        return jdbc.sql("select " + CAMPOS + " from regra_comissao where id = :id")
                .param("id", id)
                .query(RegrasDeComissaoJdbc::mapear)
                .optional();
    }

    /** Todas, na ordem da cascata — é assim que a tela de configuração as mostra. */
    @Transactional(readOnly = true)
    public List<RegraDeComissao> todas() {
        return jdbc.sql("select " + CAMPOS + """
                  from regra_comissao
                 order by case escopo
                            when 'PROFISSIONAL_SERVICO' then 3
                            when 'SERVICO' then 2
                            else 1
                          end desc, id
                """)
                .query(RegrasDeComissaoJdbc::mapear)
                .list();
    }

    private static RegraDeComissao mapear(ResultSet rs, int linha) throws SQLException {
        return new RegraDeComissao(
                rs.getObject("id", UUID.class),
                EscopoDaRegra.valueOf(rs.getString("escopo")),
                rs.getObject("profissional_id", UUID.class),
                rs.getObject("servico_id", UUID.class),
                rs.getBigDecimal("percentual"));
    }
}
