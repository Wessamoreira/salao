package br.com.salao.iam.internal.infra;

import br.com.salao.iam.internal.domain.RefreshTokenArmazenado;
import br.com.salao.shared.manutencao.ConexaoDeManutencao;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** RT-IAM-003 — persistência dos refresh tokens. */
public class RefreshTokensJdbc {

    private static final String POR_HASH = """
            select id, estabelecimento_id, usuario_id, familia_id,
                   expira_em, usado_em, revogado_em
              from refresh_token
             where token_hash = :hash
            """;

    /**
     * Marca como usado <strong>condicionalmente</strong>. É esta cláusula que arbitra a corrida
     * entre dois refresh simultâneos com o mesmo token: exatamente um consegue atualizar, o outro
     * recebe zero linhas. Sem ela, os dois rotacionariam e a cadeia se dividiria em duas famílias
     * válidas — que é o oposto do que a detecção de reuso quer garantir.
     */
    private static final String MARCAR_USADO = """
            update refresh_token
               set usado_em = :agora
             where id = :id and usado_em is null and revogado_em is null
            """;

    private static final String CRIAR = """
            insert into refresh_token
                (estabelecimento_id, usuario_id, familia_id, token_hash, expira_em, ip, user_agent)
            values (:tenant, :usuario, :familia, :hash, :expira, cast(:ip as inet), :userAgent)
            returning id
            """;

    private static final String LIGAR_SUBSTITUTO = """
            update refresh_token set substituido_por = :novo where id = :antigo
            """;

    private static final String REVOGAR_FAMILIA = """
            update refresh_token
               set revogado_em = :agora, motivo_revogacao = :motivo
             where familia_id = :familia and revogado_em is null
            """;

    private static final String REVOGAR_DO_USUARIO = """
            update refresh_token
               set revogado_em = :agora, motivo_revogacao = :motivo
             where usuario_id = :usuario and revogado_em is null
            """;

    private static final String PURGAR = """
            delete from refresh_token
             where expira_em < now() - cast(:retencao as interval)
            """;

    private final ConexaoDeManutencao plataforma;
    private final JdbcClient aplicacao;

    public RefreshTokensJdbc(ConexaoDeManutencao plataforma, JdbcClient aplicacao) {
        this.plataforma = plataforma;
        this.aplicacao = aplicacao;
    }

    /**
     * Busca pela conexão de plataforma: o refresh chega sem access token — a sessão expirada é o
     * motivo de ele existir —, então o tenant só é conhecido depois de encontrar o registro.
     * Mesmo padrão do login, e igualmente estreito.
     */
    public Optional<RefreshTokenArmazenado> porHash(String hash) {
        return plataforma.jdbc().sql(POR_HASH)
                .param("hash", hash)
                .query((rs, linha) -> new RefreshTokenArmazenado(
                        rs.getObject("id", UUID.class),
                        rs.getObject("estabelecimento_id", UUID.class),
                        rs.getObject("usuario_id", UUID.class),
                        rs.getObject("familia_id", UUID.class),
                        rs.getTimestamp("expira_em").toInstant(),
                        instanteOuNulo(rs.getTimestamp("usado_em")),
                        instanteOuNulo(rs.getTimestamp("revogado_em"))))
                .optional();
    }

    private static Instant instanteOuNulo(java.sql.Timestamp valor) {
        return valor == null ? null : valor.toInstant();
    }

    /** Emite o primeiro token de uma família nova (login). */
    @Transactional
    public UUID emitirNovaFamilia(UUID tenant, UUID usuarioId, UUID familiaId, String hash,
                                  Instant expiraEm, String ip, String userAgent) {
        return criar(tenant, usuarioId, familiaId, hash, expiraEm, ip, userAgent);
    }

    /**
     * Rotaciona: invalida o atual e emite o próximo da mesma família, numa transação só.
     *
     * <p>A ordem importa. Marca o antigo como usado <em>antes</em> de criar o novo: se fosse ao
     * contrário e a marcação perdesse a corrida, o token recém-criado já estaria valendo e
     * ficaria órfão — válido, sem ninguém do outro lado esperando por ele.
     *
     * @return o id do novo token, ou {@code Optional.empty()} se perdeu a corrida
     */
    @Transactional
    public Optional<UUID> rotacionar(RefreshTokenArmazenado atual, String hashNovo,
                                     Instant agora, Instant expiraEm, String ip,
                                     String userAgent) {
        int linhas = aplicacao.sql(MARCAR_USADO)
                .param("id", atual.id())
                .param("agora", java.sql.Timestamp.from(agora))
                .update();
        if (linhas == 0) {
            return Optional.empty();
        }

        UUID novoId = criar(atual.estabelecimentoId(), atual.usuarioId(), atual.familiaId(),
                hashNovo, expiraEm, ip, userAgent);
        aplicacao.sql(LIGAR_SUBSTITUTO)
                .param("novo", novoId)
                .param("antigo", atual.id())
                .update();
        return Optional.of(novoId);
    }

    /** Derruba a cadeia inteira. Usado quando um token já rotacionado reaparece. */
    @Transactional
    public int revogarFamilia(UUID familiaId, Instant agora, String motivo) {
        return aplicacao.sql(REVOGAR_FAMILIA)
                .param("familia", familiaId)
                .param("agora", java.sql.Timestamp.from(agora))
                .param("motivo", motivo)
                .update();
    }

    /** Encerra todas as sessões do usuário — o "sair de todos os dispositivos". */
    @Transactional
    public int revogarTodasDoUsuario(UUID usuarioId, Instant agora, String motivo) {
        return aplicacao.sql(REVOGAR_DO_USUARIO)
                .param("usuario", usuarioId)
                .param("agora", java.sql.Timestamp.from(agora))
                .param("motivo", motivo)
                .update();
    }

    /**
     * Expurgo, pela conexão de plataforma: alcança todos os estabelecimentos por natureza.
     *
     * <p>Guarda os vencidos por um tempo além do vencimento de propósito — é o que permite
     * responder "esta sessão foi encerrada quando?" numa investigação. Depois disso não serve
     * nem para auditoria, e a tabela cresce a cada login.
     */
    public int purgarVencidos(java.time.Duration retencaoAlemDoVencimento) {
        return plataforma.jdbc().sql(PURGAR)
                .param("retencao", retencaoAlemDoVencimento.toSeconds() + " seconds")
                .update();
    }

    private UUID criar(UUID tenant, UUID usuarioId, UUID familiaId, String hash,
                       Instant expiraEm, String ip, String userAgent) {
        return aplicacao.sql(CRIAR)
                .param("tenant", tenant)
                .param("usuario", usuarioId)
                .param("familia", familiaId)
                .param("hash", hash)
                .param("expira", java.sql.Timestamp.from(expiraEm))
                .param("ip", ip)
                .param("userAgent", userAgent)
                .query(UUID.class)
                .single();
    }
}
