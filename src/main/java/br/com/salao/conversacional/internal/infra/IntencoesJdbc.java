package br.com.salao.conversacional.internal.infra;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * RT-CNV-005 — as intenções pendentes, dentro do escopo do salão.
 *
 * <p>Toda decisão é condicionada a {@code status = 'PENDENTE'}: a intenção que outra conversa já
 * decidiu não é decidida de novo. E nada aqui altera o que foi proposto — nem poderia: a aplicação só
 * tem {@code update} nas colunas da decisão (V54).
 *
 * <p>Os métodos {@code MANDATORY} só fazem sentido dentro de uma transação maior (a trava e a linha
 * travada valem até o commit dela); fora de uma, falham em vez de travar nada.
 */
public class IntencoesJdbc {

    public enum Status {
        PENDENTE,
        CONFIRMADA,
        RECUSADA,
        SUBSTITUIDA,
        EXPIRADA
    }

    /** O que se precisa saber para decidir. */
    public record Intencao(UUID id, UUID usuarioEfetivoId, String ferramenta, JsonNode comando,
                           Status status, Instant expiraEm) {
    }

    public record Nova(UUID execucaoId, String telefone, UUID usuarioEfetivoId, String ferramenta,
                       JsonNode argumentos, JsonNode comando, String resumo, String pedido,
                       String codigo, Instant criadaEm, Instant expiraEm) {
    }

    private static final String COLUNAS = """
            id, usuario_efetivo_id, ferramenta, comando::text as comando, status, expira_em""";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public IntencoesJdbc(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Serializa as propostas do mesmo telefone até o fim da transação.
     *
     * <p>Duas conversas simultâneas do mesmo número passariam juntas por "encerrar a pendente", e a
     * segunda esbarraria no índice único. Com a trava, a segunda espera e encerra a da primeira — como
     * se tivessem chegado uma depois da outra.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void travarTelefone(UUID estabelecimentoId, String telefone) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:chave, 0))")
                .param("chave", "intencao:" + estabelecimentoId + ":" + telefone)
                .query((rs, n) -> 1)
                .list();
    }

    @Transactional(readOnly = true)
    public List<String> codigosDesde(String telefone, Instant desde) {
        return jdbc.sql("""
                select codigo from intencao_pendente
                 where de_telefone = :telefone and criada_em >= :desde
                """)
                .param("telefone", telefone)
                .param("desde", Timestamp.from(desde))
                .query(String.class)
                .list();
    }

    /** Encerra a pendente do telefone: vencida vira EXPIRADA, a que ainda valia vira SUBSTITUIDA. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int encerrarPendente(String telefone, Instant agora) {
        return jdbc.sql("""
                update intencao_pendente
                   set status = case when expira_em <= :agora then 'EXPIRADA' else 'SUBSTITUIDA' end,
                       decidida_em = :agora
                 where de_telefone = :telefone and status = 'PENDENTE'
                """)
                .param("telefone", telefone)
                .param("agora", Timestamp.from(agora))
                .update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID inserir(UUID estabelecimentoId, Nova nova) {
        return jdbc.sql("""
                insert into intencao_pendente
                    (estabelecimento_id, execucao_id, de_telefone, usuario_efetivo_id, ferramenta,
                     argumentos, comando, resumo, pedido_de_confirmacao, codigo, criada_em, expira_em)
                values (:estabelecimento, :execucao, :telefone, :usuario, :ferramenta,
                        cast(:argumentos as jsonb), cast(:comando as jsonb), :resumo, :pedido,
                        :codigo, :criada, :expira)
                returning id
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("execucao", nova.execucaoId())
                .param("telefone", nova.telefone())
                .param("usuario", nova.usuarioEfetivoId())
                .param("ferramenta", nova.ferramenta())
                .param("argumentos", json.writeValueAsString(nova.argumentos()))
                .param("comando", json.writeValueAsString(nova.comando()))
                .param("resumo", nova.resumo())
                .param("pedido", nova.pedido())
                .param("codigo", nova.codigo())
                .param("criada", Timestamp.from(nova.criadaEm()))
                .param("expira", Timestamp.from(nova.expiraEm()))
                .query(UUID.class)
                .single();
    }

    /** A mais recente deste telefone com este código, em qualquer situação — sem travar. */
    @Transactional(readOnly = true)
    public Optional<Intencao> maisRecente(String telefone, String codigo) {
        return jdbc.sql("select " + COLUNAS + """
                 from intencao_pendente
                 where de_telefone = :telefone and codigo = :codigo
                 order by criada_em desc
                 limit 1
                """)
                .param("telefone", telefone)
                .param("codigo", codigo)
                .query(this::intencao)
                .optional();
    }

    /** Trava a linha até o fim da transação: duas confirmações da mesma intenção se enfileiram. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Intencao> travar(UUID id) {
        return jdbc.sql("select " + COLUNAS + " from intencao_pendente where id = :id for update")
                .param("id", id)
                .query(this::intencao)
                .optional();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int confirmar(UUID id, UUID execucaoId, String resposta, JsonNode resultado,
                         Instant agora) {
        return jdbc.sql("""
                update intencao_pendente
                   set status = 'CONFIRMADA', decidida_em = :agora,
                       decidida_por_execucao_id = :execucao, resposta = :resposta,
                       resultado = cast(:resultado as jsonb)
                 where id = :id and status = 'PENDENTE'
                """)
                .param("id", id)
                .param("execucao", execucaoId)
                .param("resposta", resposta)
                .param("resultado", json.writeValueAsString(resultado))
                .param("agora", Timestamp.from(agora))
                .update();
    }

    @Transactional
    public int recusar(UUID id, UUID execucaoId, String motivo, String resposta, Instant agora) {
        return jdbc.sql("""
                update intencao_pendente
                   set status = 'RECUSADA', decidida_em = :agora,
                       decidida_por_execucao_id = :execucao, motivo = :motivo, resposta = :resposta
                 where id = :id and status = 'PENDENTE'
                """)
                .param("id", id)
                .param("execucao", execucaoId)
                .param("motivo", motivo)
                .param("resposta", resposta)
                .param("agora", Timestamp.from(agora))
                .update();
    }

    @Transactional
    public int expirar(UUID id, UUID execucaoId, Instant agora) {
        return jdbc.sql("""
                update intencao_pendente
                   set status = 'EXPIRADA', decidida_em = :agora,
                       decidida_por_execucao_id = :execucao
                 where id = :id and status = 'PENDENTE'
                """)
                .param("id", id)
                .param("execucao", execucaoId)
                .param("agora", Timestamp.from(agora))
                .update();
    }

    private Intencao intencao(ResultSet rs, int linha) throws SQLException {
        return new Intencao(rs.getObject("id", UUID.class),
                rs.getObject("usuario_efetivo_id", UUID.class),
                rs.getString("ferramenta"),
                json.readTree(rs.getString("comando")),
                Status.valueOf(rs.getString("status")),
                rs.getObject("expira_em", OffsetDateTime.class).toInstant());
    }
}
