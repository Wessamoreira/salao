package br.com.salao.iam.internal.infra;

import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.api.UsuarioResumo;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-IAM-007 — gestão de usuários.
 *
 * <p>Separado de {@link CredenciaisJdbc} de propósito: aquele é o caminho da autenticação e chega
 * a atravessar estabelecimentos pela conexão de plataforma. Este é gestão dentro de um
 * estabelecimento, sempre sob RLS. Misturar os dois faria a consulta cross-tenant conviver com
 * consultas comuns, e a distinção deixaria de ser óbvia para quem lê.
 */
public class UsuariosJdbc {

    private static final String LISTAR = """
            select id, nome, email, perfil, ativo, mfa_ativo, ultimo_acesso_em
              from usuario order by ativo desc, nome
            """;

    private static final String CRIAR = """
            insert into usuario
                (estabelecimento_id, nome, email, email_normalizado, senha_hash, perfil)
            values (:tenant, :nome, :email, :emailNormalizado, :senhaHash, :perfil)
            returning id
            """;

    private static final String ALTERAR_PERFIL =
            "update usuario set perfil = :perfil, versao = versao + 1 where id = :id";

    private static final String DEFINIR_ATIVACAO =
            "update usuario set ativo = :ativo, versao = versao + 1 where id = :id";

    private static final String TROCAR_SENHA = """
            update usuario
               set senha_hash = :hash, falhas_consecutivas = 0, bloqueado_ate = null,
                   versao = versao + 1
             where id = :id
            """;

    private static final String CONTAR_ADMINS_ATIVOS =
            "select count(*) from usuario where perfil = 'ADMIN' and ativo and id <> :exceto";

    private final JdbcClient jdbc;

    public UsuariosJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<UsuarioResumo> listar() {
        return jdbc.sql(LISTAR).query((rs, linha) -> new UsuarioResumo(
                rs.getObject("id", UUID.class),
                rs.getString("nome"),
                rs.getString("email"),
                Perfil.valueOf(rs.getString("perfil")),
                rs.getBoolean("ativo"),
                rs.getBoolean("mfa_ativo"),
                rs.getTimestamp("ultimo_acesso_em") == null
                        ? null : rs.getTimestamp("ultimo_acesso_em").toInstant())).list();
    }

    @Transactional
    public UUID criar(UUID tenant, String nome, String email, String emailNormalizado,
                      String senhaHash, Perfil perfil) {
        return jdbc.sql(CRIAR)
                .param("tenant", tenant).param("nome", nome).param("email", email)
                .param("emailNormalizado", emailNormalizado).param("senhaHash", senhaHash)
                .param("perfil", perfil.name())
                .query(UUID.class).single();
    }

    @Transactional
    public int alterarPerfil(UUID usuarioId, Perfil perfil) {
        return jdbc.sql(ALTERAR_PERFIL)
                .param("id", usuarioId).param("perfil", perfil.name()).update();
    }

    @Transactional
    public int definirAtivacao(UUID usuarioId, boolean ativo) {
        return jdbc.sql(DEFINIR_ATIVACAO).param("id", usuarioId).param("ativo", ativo).update();
    }

    @Transactional
    public int trocarSenha(UUID usuarioId, String hash) {
        return jdbc.sql(TROCAR_SENHA).param("id", usuarioId).param("hash", hash).update();
    }

    /**
     * Quantos administradores ativos existiriam <em>além</em> deste.
     *
     * <p>É o que impede o estabelecimento de ficar sem ninguém que possa administrá-lo — a
     * situação em que a saída seria mexer no banco à mão.
     */
    @Transactional(readOnly = true)
    public long outrosAdminsAtivos(UUID exceto) {
        return jdbc.sql(CONTAR_ADMINS_ATIVOS).param("exceto", exceto).query(Long.class).single();
    }
}
