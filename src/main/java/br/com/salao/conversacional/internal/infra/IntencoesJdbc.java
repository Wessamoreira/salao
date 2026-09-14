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
 * RT-CNV-005 / RT-CNV-006 — as intenções pendentes, dentro do escopo do salão.
 *
 * <p>Toda decisão é condicionada ao estado de onde ela parte: a intenção que outra conversa já
 * decidiu não é decidida de novo. E nada aqui altera o que foi proposto — nem poderia: a aplicação só
 * tem {@code update} nas colunas da decisão (V54, V55).
 *
 * <p>Os métodos {@code MANDATORY} só fazem sentido dentro de uma transação maior (a trava e a linha
 * travada valem até o commit dela); fora de uma, falham em vez de travar nada.
 */
public class IntencoesJdbc {

    public enum Status {
        PENDENTE,
        AGUARDANDO_ACEITE,
        CONFIRMADA,
        RECUSADA,
        SUBSTITUIDA,
        EXPIRADA
    }

    /**
     * RT-CNV-006 — quem precisa aceitar.
     *
     * @param expiraEm só existe depois do SIM de quem pediu; nulo antes
     */
    public record Aceite(String nome, String telefone, String motivo, String token,
                        Instant expiraEm) {
    }

    /** O que se precisa saber para decidir. */
    public record Intencao(UUID id, UUID usuarioEfetivoId, String deTelefone, String ferramenta,
                           JsonNode comando, String resumo, Status status, Instant expiraEm,
                           Aceite aceite) {

        public boolean exigeAceite() {
            return aceite != null;
        }
    }

    /** @param aceite nulo quando ninguém além de quem pediu precisa aceitar */
    public record Nova(UUID execucaoId, String telefone, UUID usuarioEfetivoId, String ferramenta,
                       JsonNode argumentos, JsonNode comando, String resumo, String pedido,
                       String codigo, Instant criadaEm, Instant expiraEm, Aceite aceite) {
    }

    /** O pedido de confirmação de uma intenção ainda pendente, para entregar. */
    public record PedidoPendente(UUID id, String texto) {
    }

    private static final String COLUNAS = """
            id, usuario_efetivo_id, de_telefone, ferramenta, comando::text as comando, resumo,
            status, expira_em, aceite_de_nome, aceite_de_telefone, aceite_motivo, aceite_token,
            aceite_expira_em""";

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
        Aceite aceite = nova.aceite();
        return jdbc.sql("""
                insert into intencao_pendente
                    (estabelecimento_id, execucao_id, de_telefone, usuario_efetivo_id, ferramenta,
                     argumentos, comando, resumo, pedido_de_confirmacao, codigo, criada_em, expira_em,
                     aceite_de_nome, aceite_de_telefone, aceite_motivo, aceite_token)
                values (:estabelecimento, :execucao, :telefone, :usuario, :ferramenta,
                        cast(:argumentos as jsonb), cast(:comando as jsonb), :resumo, :pedido,
                        :codigo, :criada, :expira, :aceiteNome, :aceiteTelefone, :aceiteMotivo,
                        :aceiteToken)
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
                .param("aceiteNome", aceite == null ? null : aceite.nome())
                .param("aceiteTelefone", aceite == null ? null : aceite.telefone())
                .param("aceiteMotivo", aceite == null ? null : aceite.motivo())
                .param("aceiteToken", aceite == null ? null : aceite.token())
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

    /**
     * RT-CNV-006 — o pedido de confirmação que esta conversa propôs, se ainda estiver pendente.
     *
     * <p>É lido ao ENCERRAR a conversa, para sair depois da resposta do modelo — e só se a conversa
     * terminou: a que parou no teto não entrega pedido a quem não recebeu resposta.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PedidoPendente> pendenteDaExecucao(UUID execucaoId) {
        return jdbc.sql("""
                select id, pedido_de_confirmacao from intencao_pendente
                 where execucao_id = :execucao and status = 'PENDENTE'
                 order by criada_em desc
                 limit 1
                """)
                .param("execucao", execucaoId)
                .query((rs, n) -> new PedidoPendente(rs.getObject("id", UUID.class),
                        rs.getString("pedido_de_confirmacao")))
                .optional();
    }

    /** Trava a linha até o fim da transação: duas decisões da mesma intenção se enfileiram. */
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

    // ------------------------------------------------------------------ RT-CNV-006

    /** O SIM de quem pediu: a intenção passa a esperar quem vai atender, com prazo. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int pedirAceite(UUID id, UUID execucaoId, String resposta, Instant agora,
                           Instant expiraEm) {
        return jdbc.sql("""
                update intencao_pendente
                   set status = 'AGUARDANDO_ACEITE', decidida_em = :agora,
                       decidida_por_execucao_id = :execucao, resposta = :resposta,
                       aceite_expira_em = :expira
                 where id = :id and status = 'PENDENTE' and aceite_token is not null
                """)
                .param("id", id)
                .param("execucao", execucaoId)
                .param("resposta", resposta)
                .param("agora", Timestamp.from(agora))
                .param("expira", Timestamp.from(expiraEm))
                .update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int confirmarAceite(UUID id, UUID execucaoId, String resposta, JsonNode resultado,
                               Instant agora) {
        return jdbc.sql("""
                update intencao_pendente
                   set status = 'CONFIRMADA', aceite_decidido_em = :agora,
                       aceite_por_execucao_id = :execucao, resposta = :resposta,
                       resultado = cast(:resultado as jsonb)
                 where id = :id and status = 'AGUARDANDO_ACEITE'
                """)
                .param("id", id)
                .param("execucao", execucaoId)
                .param("resposta", resposta)
                .param("resultado", json.writeValueAsString(resultado))
                .param("agora", Timestamp.from(agora))
                .update();
    }

    /** Recusada por quem vai atender, ou pela gravação no momento do aceite. */
    @Transactional
    public int recusarNoAceite(UUID id, UUID execucaoId, String motivo, String resposta,
                               Instant agora) {
        return jdbc.sql("""
                update intencao_pendente
                   set status = 'RECUSADA', motivo = :motivo, resposta = :resposta,
                       aceite_decidido_em = :agora, aceite_por_execucao_id = :execucao
                 where id = :id and status = 'AGUARDANDO_ACEITE'
                """)
                .param("id", id)
                .param("execucao", execucaoId)
                .param("motivo", motivo)
                .param("resposta", resposta)
                .param("agora", Timestamp.from(agora))
                .update();
    }

    @Transactional
    public int expirarAceite(UUID id, UUID execucaoId, Instant agora) {
        return jdbc.sql("""
                update intencao_pendente
                   set status = 'EXPIRADA', aceite_decidido_em = :agora,
                       aceite_por_execucao_id = :execucao
                 where id = :id and status = 'AGUARDANDO_ACEITE'
                """)
                .param("id", id)
                .param("execucao", execucaoId)
                .param("agora", Timestamp.from(agora))
                .update();
    }

    private Intencao intencao(ResultSet rs, int linha) throws SQLException {
        Aceite aceite = null;
        if (rs.getString("aceite_token") != null) {
            OffsetDateTime expira = rs.getObject("aceite_expira_em", OffsetDateTime.class);
            aceite = new Aceite(rs.getString("aceite_de_nome"), rs.getString("aceite_de_telefone"),
                    rs.getString("aceite_motivo"), rs.getString("aceite_token"),
                    expira == null ? null : expira.toInstant());
        }
        return new Intencao(rs.getObject("id", UUID.class),
                rs.getObject("usuario_efetivo_id", UUID.class),
                rs.getString("de_telefone"),
                rs.getString("ferramenta"),
                json.readTree(rs.getString("comando")),
                rs.getString("resumo"),
                Status.valueOf(rs.getString("status")),
                rs.getObject("expira_em", OffsetDateTime.class).toInstant(),
                aceite);
    }
}
