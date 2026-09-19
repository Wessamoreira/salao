package br.com.salao.conversacional.internal.infra;

import br.com.salao.conversacional.internal.application.ConsultarAcoesDoAgenteUseCase.Recorte;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * RT-CNV-007 — a trilha do agente, dentro do escopo do salão.
 *
 * <p>A aplicação só insere e lê ({@code V56}). Registrar é {@code MANDATORY}: a linha nasce no mesmo
 * commit em que a conversa se encerra — separada, uma queda entre as duas deixaria uma conversa
 * encerrada sem rastro, e nenhuma rodada seguinte a reencontraria.
 */
public class AcoesDoAgenteJdbc {

    /**
     * O retrato, montado no banco a partir do que a conversa deixou.
     *
     * <p>Num comando só, e lendo o estado JÁ encerrado: se a execução ainda estiver pendente, não
     * registra nada. {@code on conflict} torna a segunda chamada inofensiva.
     */
    private static final String REGISTRAR = """
            insert into acao_ia
                (estabelecimento_id, execucao_id, ocorrida_em, tipo, desfecho, motivo_recusa,
                 usuario_efetivo_id, de_telefone, veio_de_audio, pedido, resposta, ferramentas,
                 intencoes, gravou, bloqueada, modelo, tokens_entrada, tokens_saida,
                 custo_estimado_usd)
            select e.estabelecimento_id, e.id, :agora, e.tipo, e.status, e.motivo_recusa,
                   e.usuario_efetivo_id, e.de_telefone, m.tipo = 'AUDIO',
                   case when e.tipo = 'BOTAO' then
                        case when e.entrada like '%:sim' then 'Aceitar'
                             when e.entrada like '%:nao' then 'Recusar'
                             else 'Botão' end
                        else e.entrada end,
                   e.resposta, e.ferramentas,
                   coalesce(i.intencoes, '[]'::jsonb),
                   coalesce(i.gravou, false),
                   e.status = 'RECUSADA'
                     or e.ferramentas @> '[{"desfecho":"INEXISTENTE"}]'
                     or e.ferramentas @> '[{"desfecho":"FORA_DA_LISTA"}]'
                     or e.ferramentas @> '[{"desfecho":"SEM_PERMISSAO"}]',
                   e.modelo, e.tokens_entrada, e.tokens_saida, e.custo_estimado_usd
              from execucao_do_agente e
              join mensagem_recebida m on m.id = e.mensagem_id
              left join lateral (
                   select jsonb_agg(jsonb_build_object(
                              'ferramenta', x.ferramenta, 'resumo', x.resumo, 'status', x.status,
                              'motivo', x.motivo,
                              'papel', case when x.aceite_por_execucao_id = e.id then 'ACEITE'
                                            when x.decidida_por_execucao_id = e.id then 'DECISAO'
                                            else 'PROPOSTA' end)
                              order by x.criada_em) as intencoes,
                          bool_or(x.status = 'CONFIRMADA'
                                  and ((x.aceite_token is null and x.decidida_por_execucao_id = e.id)
                                       or x.aceite_por_execucao_id = e.id)) as gravou
                     from intencao_pendente x
                    where x.execucao_id = e.id
                       or x.decidida_por_execucao_id = e.id
                       or x.aceite_por_execucao_id = e.id) i on true
             where e.id = :execucao and e.status <> 'PENDENTE'
            on conflict (execucao_id) do nothing
            """;

    private static final String COLUNAS = """
            id, ocorrida_em, tipo, desfecho, motivo_recusa, usuario_efetivo_id, de_telefone,
            veio_de_audio, pedido, resposta, ferramentas::text as ferramentas,
            intencoes::text as intencoes, gravou, bloqueada, modelo, tokens_entrada, tokens_saida,
            custo_estimado_usd, anonimizada_em""";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AcoesDoAgenteJdbc(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record Linha(UUID id, Instant ocorridaEm, String tipo, String desfecho,
                        String motivoRecusa, UUID usuarioEfetivoId, String deTelefone,
                        boolean veioDeAudio, String pedido, String resposta, JsonNode ferramentas,
                        JsonNode intencoes, boolean gravou, boolean bloqueada, String modelo,
                        int tokensEntrada, int tokensSaida, BigDecimal custoUsd,
                        Instant anonimizadaEm) {
    }

    /** O recorte, o período ({@code [de, ate)}) e, opcionalmente, a pessoa. */
    public record Consulta(Recorte recorte, UUID usuarioId, Instant de, Instant ate) {
    }

    public record Totais(long conversas, long gravaram, long bloqueadas, BigDecimal custoUsd,
                         long tokensEntrada, long tokensSaida) {
    }

    public record Participacao(UUID usuarioId, long conversas) {
    }

    /** @return verdadeiro se registrou agora */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean registrar(UUID execucaoId, Instant agora) {
        return jdbc.sql(REGISTRAR)
                .param("execucao", execucaoId)
                .param("agora", Timestamp.from(agora))
                .update() == 1;
    }

    /**
     * Uma página, das mais recentes para trás.
     *
     * @param antesDe   o cursor: só o que veio antes deste instante e id; nulo na primeira página
     */
    @Transactional(readOnly = true)
    public List<Linha> listar(Consulta consulta, Instant antesDe, UUID antesDoId, int quantos) {
        var sql = new StringBuilder("select " + COLUNAS + " from acao_ia where ")
                .append(condicoes(consulta, true));
        if (antesDe != null) {
            sql.append(" and (ocorrida_em, id) < (:antesDe, :antesDoId)");
        }
        sql.append(" order by ocorrida_em desc, id desc limit :quantos");
        var comando = parametros(jdbc.sql(sql.toString()), consulta).param("quantos", quantos);
        if (antesDe != null) {
            comando = comando.param("antesDe", Timestamp.from(antesDe)).param("antesDoId", antesDoId);
        }
        return comando.query(this::linha).list();
    }

    /** Os totais do período e da pessoa — sem o recorte, que é o que eles decompõem. */
    @Transactional(readOnly = true)
    public Totais totais(Consulta consulta) {
        return parametros(jdbc.sql("""
                select count(*) as conversas,
                       count(*) filter (where gravou) as gravaram,
                       count(*) filter (where bloqueada) as bloqueadas,
                       coalesce(sum(custo_estimado_usd), 0) as custo,
                       coalesce(sum(tokens_entrada), 0) as entrada,
                       coalesce(sum(tokens_saida), 0) as saida
                  from acao_ia
                 where %s
                """.formatted(condicoes(consulta, false))), consulta)
                .query((rs, n) -> new Totais(rs.getLong("conversas"), rs.getLong("gravaram"),
                        rs.getLong("bloqueadas"), rs.getBigDecimal("custo"), rs.getLong("entrada"),
                        rs.getLong("saida")))
                .single();
    }

    /** Quem conversou com o agente no período, de quem mais para quem menos. */
    @Transactional(readOnly = true)
    public List<Participacao> participantes(Instant de, Instant ate) {
        return jdbc.sql("""
                select usuario_efetivo_id, count(*) as conversas from acao_ia
                 where ocorrida_em >= :de and ocorrida_em < :ate and usuario_efetivo_id is not null
                 group by usuario_efetivo_id
                 order by conversas desc, usuario_efetivo_id
                """)
                .param("de", Timestamp.from(de))
                .param("ate", Timestamp.from(ate))
                .query((rs, n) -> new Participacao(rs.getObject("usuario_efetivo_id", UUID.class),
                        rs.getLong("conversas")))
                .list();
    }

    /**
     * Só fragmentos fixos: nada do que chega de fora é concatenado ao SQL.
     *
     * <p>Encaixado com {@code %s}, e não somando strings ao fim de um bloco de texto: o bloco apaga
     * o espaço no fim da linha, e {@code "where " + condicoes} virava {@code whereocorrida_em}.
     */
    private static String condicoes(Consulta consulta, boolean comRecorte) {
        var sql = new StringBuilder("ocorrida_em >= :de and ocorrida_em < :ate");
        if (consulta.usuarioId() != null) {
            sql.append(" and usuario_efetivo_id = :usuario");
        }
        if (comRecorte && consulta.recorte() == Recorte.GRAVARAM) {
            sql.append(" and gravou");
        } else if (comRecorte && consulta.recorte() == Recorte.BLOQUEADAS) {
            sql.append(" and bloqueada");
        }
        return sql.toString();
    }

    private static JdbcClient.StatementSpec parametros(JdbcClient.StatementSpec comando,
                                                       Consulta consulta) {
        comando = comando.param("de", Timestamp.from(consulta.de()))
                .param("ate", Timestamp.from(consulta.ate()));
        if (consulta.usuarioId() != null) {
            comando = comando.param("usuario", consulta.usuarioId());
        }
        return comando;
    }

    private Linha linha(ResultSet rs, int n) throws SQLException {
        OffsetDateTime anonimizada = rs.getObject("anonimizada_em", OffsetDateTime.class);
        return new Linha(rs.getObject("id", UUID.class),
                rs.getObject("ocorrida_em", OffsetDateTime.class).toInstant(),
                rs.getString("tipo"), rs.getString("desfecho"), rs.getString("motivo_recusa"),
                rs.getObject("usuario_efetivo_id", UUID.class), rs.getString("de_telefone"),
                rs.getBoolean("veio_de_audio"), rs.getString("pedido"), rs.getString("resposta"),
                json.readTree(rs.getString("ferramentas")), json.readTree(rs.getString("intencoes")),
                rs.getBoolean("gravou"), rs.getBoolean("bloqueada"), rs.getString("modelo"),
                rs.getInt("tokens_entrada"), rs.getInt("tokens_saida"),
                rs.getBigDecimal("custo_estimado_usd"),
                anonimizada == null ? null : anonimizada.toInstant());
    }
}
