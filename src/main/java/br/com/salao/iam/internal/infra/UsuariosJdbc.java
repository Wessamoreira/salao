package br.com.salao.iam.internal.infra;

import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.api.UsuarioResumo;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
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

    private static final String CAMPOS_DO_RESUMO =
            "id, nome, email, telefone, perfil, ativo, mfa_ativo, ultimo_acesso_em";

    private static final String LISTAR =
            "select " + CAMPOS_DO_RESUMO + " from usuario order by ativo desc, nome";

    private static final String POR_ID =
            "select " + CAMPOS_DO_RESUMO + " from usuario where id = :id";

    private static final String POR_TELEFONE = """
            select id, nome, perfil, ativo, bloqueado_ate from usuario where telefone = :telefone
            """;

    private static final String DEFINIR_TELEFONE =
            "update usuario set telefone = :telefone, versao = versao + 1 where id = :id";

    private static final String CRIAR = """
            insert into usuario
                (estabelecimento_id, nome, email, email_normalizado, senha_hash, perfil)
            values (:tenant, :nome, :email, :emailNormalizado, :senhaHash, :perfil)
            returning id
            """;

    /** RN-IAM-024 — só o que é ficha. Perfil, ativação e senha têm comando próprio. */
    private static final String EDITAR = """
            update usuario
               set nome = :nome, email = :email, email_normalizado = :emailNormalizado,
                   versao = versao + 1
             where id = :id
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
        return jdbc.sql(LISTAR).query(this::resumo).list();
    }

    @Transactional(readOnly = true)
    public Optional<UsuarioResumo> porId(UUID usuarioId) {
        return jdbc.sql(POR_ID).param("id", usuarioId).query(this::resumo).optional();
    }

    private UsuarioResumo resumo(java.sql.ResultSet rs, int linha) throws java.sql.SQLException {
        return new UsuarioResumo(
                rs.getObject("id", UUID.class),
                rs.getString("nome"),
                rs.getString("email"),
                rs.getString("telefone"),
                Perfil.valueOf(rs.getString("perfil")),
                rs.getBoolean("ativo"),
                rs.getBoolean("mfa_ativo"),
                rs.getTimestamp("ultimo_acesso_em") == null
                        ? null : rs.getTimestamp("ultimo_acesso_em").toInstant());
    }

    /**
     * RT-CNV-002 — quem deste salão tem este telefone.
     *
     * <p>Sob RLS, pela conexão da aplicação: o webhook já descobriu o salão (RN-CNV-003), e esta
     * pergunta é feita DENTRO dele. Um telefone de outro salão simplesmente não é visto.
     *
     * <p>Devolve o necessário para a política decidir — ativo, bloqueio, perfil — e nada de
     * autenticação: hash de senha e contador de falhas não têm o que fazer aqui.
     */
    @Transactional(readOnly = true)
    public Optional<PorTelefone> porTelefone(String telefone) {
        return jdbc.sql(POR_TELEFONE).param("telefone", telefone)
                .query((rs, linha) -> new PorTelefone(
                        rs.getObject("id", UUID.class),
                        rs.getString("nome"),
                        Perfil.valueOf(rs.getString("perfil")),
                        rs.getBoolean("ativo"),
                        rs.getTimestamp("bloqueado_ate") == null
                                ? null : rs.getTimestamp("bloqueado_ate").toInstant()))
                .optional();
    }

    @Transactional
    public int definirTelefone(UUID usuarioId, String telefone) {
        return jdbc.sql(DEFINIR_TELEFONE)
                .param("id", usuarioId).param("telefone", telefone).update();
    }

    /** O que a política do usuário efetivo precisa saber de uma conta. */
    public record PorTelefone(UUID id, String nome, Perfil perfil, boolean ativo,
                              Instant bloqueadoAte) {
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
    public int editar(UUID usuarioId, String nome, String email, String emailNormalizado) {
        return jdbc.sql(EDITAR)
                .param("id", usuarioId).param("nome", nome).param("email", email)
                .param("emailNormalizado", emailNormalizado).update();
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
