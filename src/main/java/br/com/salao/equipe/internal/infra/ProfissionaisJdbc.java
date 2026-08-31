package br.com.salao.equipe.internal.infra;

import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.equipe.api.ProfissionalResumo;
import br.com.salao.equipe.api.TipoDeVinculo;
import br.com.salao.equipe.internal.domain.Documento;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EQP-001 — persistência do profissional.
 *
 * <p>{@code @Transactional} é obrigatório mesmo na leitura: é a transação que dispara o
 * {@code set_config('app.tenant_id')}. Fora dela, a RLS não encontra o tenant e a consulta
 * devolve zero linhas.
 *
 * <p>Nenhuma consulta filtra {@code estabelecimento_id}: quem faz isso é a RLS. Repetir o filtro
 * ensinaria que ele é a defesa.
 */
public class ProfissionaisJdbc implements ProfissionalApi {

    private static final String CAMPOS = """
            id, nome, apelido, documento, tipo_vinculo, telefone, email,
            comissao_percentual, contrato_inicio, contrato_fim, contrato_assinado_em,
            ativo, versao
            """;

    private static final RowMapper<ProfissionalResumo> RESUMO = (rs, linha) ->
            new ProfissionalResumo(
                    rs.getObject("id", UUID.class),
                    rs.getString("nome"),
                    rs.getString("apelido"),
                    rs.getString("documento"),
                    TipoDeVinculo.valueOf(rs.getString("tipo_vinculo")),
                    rs.getString("telefone"),
                    rs.getString("email"),
                    rs.getBigDecimal("comissao_percentual"),
                    data(rs.getDate("contrato_inicio")),
                    data(rs.getDate("contrato_fim")),
                    data(rs.getDate("contrato_assinado_em")),
                    rs.getBoolean("ativo"),
                    rs.getInt("versao"));

    private static LocalDate data(Date valor) {
        return valor == null ? null : valor.toLocalDate();
    }

    private final JdbcClient jdbc;

    public ProfissionaisJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID inserir(UUID estabelecimentoId, String nome, String apelido, Documento documento,
                        TipoDeVinculo vinculo, String telefone, String email,
                        BigDecimal comissao, LocalDate inicio, LocalDate fim,
                        LocalDate assinadoEm, UUID criadoPor) {
        return jdbc.sql("""
                insert into profissional
                    (estabelecimento_id, nome, apelido, documento, tipo_documento, tipo_vinculo,
                     telefone, email, comissao_percentual, contrato_inicio, contrato_fim,
                     contrato_assinado_em, criado_por)
                values (:estabelecimento, :nome, :apelido, :documento, :tipoDocumento, :vinculo,
                        :telefone, :email, :comissao, :inicio, :fim, :assinadoEm, :criadoPor)
                returning id
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("nome", nome)
                .param("apelido", apelido)
                .param("documento", documento.digitos())
                .param("tipoDocumento", documento.tipo().name())
                .param("vinculo", vinculo.name())
                .param("telefone", telefone)
                .param("email", email)
                .param("comissao", comissao)
                .param("inicio", inicio)
                .param("fim", fim)
                .param("assinadoEm", assinadoEm)
                .param("criadoPor", criadoPor)
                .query(UUID.class)
                .single();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProfissionalResumo> porId(UUID profissionalId) {
        return jdbc.sql("select " + CAMPOS + " from profissional where id = :id")
                .param("id", profissionalId)
                .query(RESUMO)
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProfissionalResumo> ativos() {
        return jdbc.sql("select " + CAMPOS + " from profissional where ativo order by nome")
                .query(RESUMO)
                .list();
    }

    /** Inclui os inativos: quem administra precisa ver quem saiu para poder trazer de volta. */
    @Transactional(readOnly = true)
    public List<ProfissionalResumo> todos() {
        return jdbc.sql("select " + CAMPOS + " from profissional order by ativo desc, nome")
                .query(RESUMO)
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean estaAtivo(UUID profissionalId) {
        return !jdbc.sql("select 1 from profissional where id = :id and ativo")
                .param("id", profissionalId)
                .query(Integer.class)
                .list()
                .isEmpty();
    }

    public int definirAtivacao(UUID id, boolean ativo) {
        return jdbc.sql("""
                update profissional set ativo = :ativo, versao = versao + 1
                 where id = :id and ativo <> :ativo
                """)
                .param("id", id)
                .param("ativo", ativo)
                .update();
    }
}
