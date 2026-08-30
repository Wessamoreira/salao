package br.com.salao.iam.internal.infra;

import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.internal.domain.CredencialDeAcesso;
import br.com.salao.shared.manutencao.ConexaoDeManutencao;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-IAM-002 — leitura e escrita das credenciais.
 *
 * <p>A busca por e-mail é o <strong>único</strong> alcance cross-tenant do login: ela precisa
 * descobrir o estabelecimento antes de existir tenant no escopo. Vai pela conexão de plataforma
 * (ADR-0010) e devolve uma projeção estreita — nada além do que a decisão de autenticar exige.
 *
 * <p>Todo o resto — contar falhas, aplicar bloqueio, registrar acesso — acontece já dentro do
 * escopo do tenant, pela conexão da aplicação, sob RLS. Assim o alcance cross-tenant fica
 * reduzido a uma consulta só.
 */
public class CredenciaisJdbc {

    private static final String POR_EMAIL = """
            select id, estabelecimento_id, senha_hash, perfil, ativo,
                   falhas_consecutivas, bloqueado_ate
              from usuario
             where email_normalizado = :email
            """;

    private static final String REGISTRAR_FALHA = """
            update usuario
               set falhas_consecutivas = falhas_consecutivas + 1,
                   bloqueado_ate = :bloqueadoAte,
                   versao = versao + 1
             where id = :id
            """;

    private static final String REGISTRAR_SUCESSO = """
            update usuario
               set falhas_consecutivas = 0,
                   bloqueado_ate = null,
                   ultimo_acesso_em = now(),
                   versao = versao + 1
             where id = :id
            """;

    private final ConexaoDeManutencao plataforma;
    private final JdbcClient aplicacao;

    public CredenciaisJdbc(ConexaoDeManutencao plataforma, JdbcClient aplicacao) {
        this.plataforma = plataforma;
        this.aplicacao = aplicacao;
    }

    public Optional<CredencialDeAcesso> porEmail(String emailNormalizado) {
        return plataforma.jdbc().sql(POR_EMAIL)
                .param("email", emailNormalizado)
                .query((rs, linha) -> new CredencialDeAcesso(
                        rs.getObject("id", UUID.class),
                        rs.getObject("estabelecimento_id", UUID.class),
                        rs.getString("senha_hash"),
                        Perfil.valueOf(rs.getString("perfil")),
                        rs.getBoolean("ativo"),
                        rs.getInt("falhas_consecutivas"),
                        rs.getObject("bloqueado_ate", java.sql.Timestamp.class) == null
                                ? null
                                : rs.getTimestamp("bloqueado_ate").toInstant()))
                .optional();
    }

    /** Chamado dentro do escopo do tenant: a RLS confere que o usuário é mesmo dele. */
    @Transactional
    public void registrarFalha(UUID usuarioId, Instant bloqueadoAte) {
        aplicacao.sql(REGISTRAR_FALHA)
                .param("id", usuarioId)
                .param("bloqueadoAte",
                        bloqueadoAte == null ? null : java.sql.Timestamp.from(bloqueadoAte))
                .update();
    }

    @Transactional
    public void registrarSucesso(UUID usuarioId) {
        aplicacao.sql(REGISTRAR_SUCESSO).param("id", usuarioId).update();
    }
}
