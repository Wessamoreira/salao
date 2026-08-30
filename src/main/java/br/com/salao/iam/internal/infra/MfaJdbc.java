package br.com.salao.iam.internal.infra;

import br.com.salao.iam.internal.domain.MfaCredencial;
import br.com.salao.shared.cripto.CofreDeCampo;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** RT-IAM-005 — persistência do segundo fator. */
public class MfaJdbc {

    private static final String POR_USUARIO = """
            select id, segredo_cifrado, confirmado_em, ultimo_contador
              from mfa_credencial where usuario_id = :usuario
            """;

    private static final String SALVAR_SEGREDO = """
            insert into mfa_credencial (estabelecimento_id, usuario_id, segredo_cifrado)
            values (:tenant, :usuario, :segredo)
            on conflict (usuario_id) do update
                set segredo_cifrado = excluded.segredo_cifrado,
                    confirmado_em = null, ultimo_contador = null
            """;

    /**
     * Confirma e registra a janela usada, <strong>condicionalmente</strong>.
     *
     * <p>{@code ultimo_contador is null or ultimo_contador < :contador} é o que impede reuso: um
     * código TOTP vale trinta segundos, e sem esta cláusula quem o interceptasse poderia
     * reapresentá-lo dentro desse intervalo. Quem arbitra é o banco, como no refresh.
     */
    private static final String CONSUMIR_CONTADOR = """
            update mfa_credencial
               set ultimo_contador = :contador, confirmado_em = coalesce(confirmado_em, :agora)
             where usuario_id = :usuario
               and (ultimo_contador is null or ultimo_contador < :contador)
            """;

    private static final String ATIVAR_NO_USUARIO =
            "update usuario set mfa_ativo = :ativo, versao = versao + 1 where id = :usuario";

    private static final String REMOVER =
            "delete from mfa_credencial where usuario_id = :usuario";

    private static final String GRAVAR_RECUPERACAO = """
            insert into mfa_codigo_recuperacao (estabelecimento_id, usuario_id, codigo_hash)
            values (:tenant, :usuario, :hash)
            """;

    private static final String APAGAR_RECUPERACAO =
            "delete from mfa_codigo_recuperacao where usuario_id = :usuario";

    private static final String CONSUMIR_RECUPERACAO = """
            update mfa_codigo_recuperacao
               set usado_em = :agora
             where usuario_id = :usuario and codigo_hash = :hash and usado_em is null
            """;

    private final JdbcClient jdbc;
    private final CofreDeCampo cofre;

    public MfaJdbc(JdbcClient jdbc, CofreDeCampo cofre) {
        this.jdbc = jdbc;
        this.cofre = cofre;
    }

    @Transactional(readOnly = true)
    public Optional<MfaCredencial> porUsuario(UUID usuarioId) {
        return jdbc.sql(POR_USUARIO)
                .param("usuario", usuarioId)
                .query((rs, linha) -> new MfaCredencial(
                        rs.getObject("id", UUID.class),
                        cofre.decifrar(rs.getBytes("segredo_cifrado")),
                        rs.getTimestamp("confirmado_em") == null
                                ? null : rs.getTimestamp("confirmado_em").toInstant(),
                        rs.getObject("ultimo_contador") == null
                                ? null : rs.getLong("ultimo_contador")))
                .optional();
    }

    /** Sobrescreve qualquer inscrição anterior não confirmada — reinscrever é o caminho normal. */
    @Transactional
    public void salvarSegredo(UUID tenant, UUID usuarioId, String segredoBase32) {
        jdbc.sql(SALVAR_SEGREDO)
                .param("tenant", tenant)
                .param("usuario", usuarioId)
                .param("segredo", cofre.cifrar(segredoBase32))
                .update();
    }

    /** @return {@code false} se o contador já foi usado — reapresentação de código */
    @Transactional
    public boolean consumirContador(UUID usuarioId, long contador, Instant agora) {
        return jdbc.sql(CONSUMIR_CONTADOR)
                .param("usuario", usuarioId)
                .param("contador", contador)
                .param("agora", java.sql.Timestamp.from(agora))
                .update() > 0;
    }

    @Transactional
    public void definirAtivo(UUID usuarioId, boolean ativo) {
        jdbc.sql(ATIVAR_NO_USUARIO).param("usuario", usuarioId).param("ativo", ativo).update();
    }

    @Transactional
    public void desativar(UUID usuarioId) {
        jdbc.sql(REMOVER).param("usuario", usuarioId).update();
        jdbc.sql(APAGAR_RECUPERACAO).param("usuario", usuarioId).update();
        definirAtivo(usuarioId, false);
    }

    /** Substitui os códigos anteriores: emitir novos invalida os antigos, sempre. */
    @Transactional
    public void gravarCodigosDeRecuperacao(UUID tenant, UUID usuarioId, List<String> hashes) {
        jdbc.sql(APAGAR_RECUPERACAO).param("usuario", usuarioId).update();
        for (String hash : hashes) {
            jdbc.sql(GRAVAR_RECUPERACAO)
                    .param("tenant", tenant).param("usuario", usuarioId).param("hash", hash)
                    .update();
        }
    }

    /** @return {@code true} se o código existia e ainda não tinha sido usado */
    @Transactional
    public boolean consumirCodigoDeRecuperacao(UUID usuarioId, String hash, Instant agora) {
        return jdbc.sql(CONSUMIR_RECUPERACAO)
                .param("usuario", usuarioId).param("hash", hash)
                .param("agora", java.sql.Timestamp.from(agora))
                .update() > 0;
    }
}
