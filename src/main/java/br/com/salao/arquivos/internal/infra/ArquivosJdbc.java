package br.com.salao.arquivos.internal.infra;

import br.com.salao.arquivos.api.ArquivoResumo;
import br.com.salao.arquivos.api.StatusDoArquivo;
import br.com.salao.arquivos.api.TipoDeArquivo;
import br.com.salao.shared.jdbc.Colunas;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** RT-ARQ-001 — o registro dos arquivos. */
public class ArquivosJdbc {

    private static final String CAMPOS = """
            id, dono_id, dono_tipo, chave, nome_original, tipo, tamanho_bytes, status,
            motivo_recusa, criado_em
            """;

    private final JdbcClient jdbc;

    public ArquivosJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public UUID registrar(UUID estabelecimentoId, String donoTipo, UUID donoId, String chave,
                          String nomeOriginal, UUID criadoPor) {
        return jdbc.sql("""
                insert into arquivo (estabelecimento_id, dono_tipo, dono_id, chave,
                                     nome_original, criado_por)
                values (:estabelecimento, :donoTipo, :dono, :chave, :nome, :criadoPor)
                returning id
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("donoTipo", donoTipo)
                .param("dono", donoId)
                .param("chave", chave)
                .param("nome", nomeOriginal)
                .param("criadoPor", criadoPor)
                .query(UUID.class)
                .single();
    }

    @Transactional
    public void validar(UUID id, TipoDeArquivo tipo, long tamanho) {
        jdbc.sql("""
                update arquivo
                   set status = 'VALIDADO', tipo = :tipo, tamanho_bytes = :tamanho,
                       confirmado_em = now(), motivo_recusa = null
                 where id = :id
                """)
                .param("id", id)
                .param("tipo", tipo.name())
                .param("tamanho", tamanho)
                .update();
    }

    @Transactional
    public void recusar(UUID id, String motivo) {
        jdbc.sql("""
                update arquivo
                   set status = 'RECUSADO', motivo_recusa = :motivo, confirmado_em = now()
                 where id = :id
                """)
                .param("id", id)
                .param("motivo", motivo)
                .update();
    }

    @Transactional(readOnly = true)
    public Optional<ArquivoResumo> porId(UUID id) {
        return jdbc.sql("select " + CAMPOS + " from arquivo where id = :id")
                .param("id", id)
                .query(ArquivosJdbc::mapear)
                .optional();
    }

    /** Só os validados: um arquivo aguardando ou recusado não é conteúdo, é rascunho. */
    @Transactional(readOnly = true)
    public List<ArquivoResumo> doDono(String donoTipo, UUID donoId) {
        return jdbc.sql("select " + CAMPOS + """
                  from arquivo
                 where dono_tipo = :donoTipo and dono_id = :dono and status = 'VALIDADO'
                 order by criado_em desc
                """)
                .param("donoTipo", donoTipo)
                .param("dono", donoId)
                .query(ArquivosJdbc::mapear)
                .list();
    }

    private static ArquivoResumo mapear(java.sql.ResultSet rs, int linha)
            throws java.sql.SQLException {
        String tipo = rs.getString("tipo");
        Long tamanho = Colunas.longoOuNulo(rs, "tamanho_bytes");
        return new ArquivoResumo(
                rs.getObject("id", UUID.class),
                rs.getObject("dono_id", UUID.class),
                rs.getString("dono_tipo"),
                rs.getString("chave"),
                rs.getString("nome_original"),
                tipo == null ? null : TipoDeArquivo.valueOf(tipo),
                tamanho,
                StatusDoArquivo.valueOf(rs.getString("status")),
                rs.getString("motivo_recusa"),
                rs.getObject("criado_em", OffsetDateTime.class).toInstant());
    }
}
