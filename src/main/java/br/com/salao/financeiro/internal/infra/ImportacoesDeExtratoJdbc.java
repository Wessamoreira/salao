package br.com.salao.financeiro.internal.infra;

import br.com.salao.financeiro.internal.domain.LinhaDoExtrato;
import br.com.salao.financeiro.internal.domain.MapeamentoDoExtrato;
import br.com.salao.shared.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** RT-FIN-009 — cada importação, com as contagens e o que foi recusado. Só insere e lê. */
public class ImportacoesDeExtratoJdbc {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ImportacoesDeExtratoJdbc(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record Nova(UUID id, String adquirente, String impressao, String nomeDoArquivo,
                       Instant importadaEm, UUID importadaPor, int linhas, int novas,
                       int canceladas, int repetidas, int ignoradas, int recusadas,
                       List<LinhaDoExtrato.Recusada> recusas, MapeamentoDoExtrato mapeamento) {
    }

    public record Linha(UUID id, Instant importadaEm, String nomeDoArquivo, UUID importadaPor,
                        int linhas, int novas, int canceladas, int repetidas, int ignoradas,
                        int recusadas) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void registrar(Nova importacao) {
        jdbc.sql("""
                insert into importacao_de_extrato
                    (id, estabelecimento_id, adquirente, impressao_do_arquivo, nome_do_arquivo,
                     importada_em, importada_por, linhas, novas, canceladas, repetidas, ignoradas,
                     recusadas, recusas, mapeamento)
                values (:id, :estabelecimento, :adquirente, :impressao, :nome, :em, :por, :linhas,
                        :novas, :canceladas, :repetidas, :ignoradas, :recusadas,
                        cast(:recusas as jsonb), cast(:mapeamento as jsonb))
                """)
                .param("id", importacao.id())
                .param("estabelecimento", TenantContext.obrigatorio())
                .param("adquirente", importacao.adquirente())
                .param("impressao", importacao.impressao())
                .param("nome", importacao.nomeDoArquivo())
                .param("em", Timestamp.from(importacao.importadaEm()))
                .param("por", importacao.importadaPor())
                .param("linhas", importacao.linhas())
                .param("novas", importacao.novas())
                .param("canceladas", importacao.canceladas())
                .param("repetidas", importacao.repetidas())
                .param("ignoradas", importacao.ignoradas())
                .param("recusadas", importacao.recusadas())
                .param("recusas", json.writeValueAsString(importacao.recusas()))
                .param("mapeamento", json.writeValueAsString(Map.of(
                        "colunas", importacao.mapeamento().colunas(),
                        "formato", importacao.mapeamento().formato())))
                .update();
    }

    /** Quando este mesmo arquivo foi importado por último neste salão. */
    @Transactional(readOnly = true)
    public Optional<Instant> importadoEm(String impressao) {
        // Sem max(): ele devolve uma linha com NULL quando o arquivo é novo, e o JdbcClient recusa
        // resultado nulo. Nenhuma linha é o jeito honesto de dizer "nunca".
        return jdbc.sql("""
                select importada_em from importacao_de_extrato
                 where impressao_do_arquivo = :impressao
                 order by importada_em desc limit 1
                """)
                .param("impressao", impressao)
                .query((rs, n) -> rs.getObject(1, OffsetDateTime.class).toInstant())
                .optional();
    }

    @Transactional(readOnly = true)
    public List<Linha> listar(Instant antesDe, UUID antesDoId, int quantos) {
        String sql = """
                select id, importada_em, nome_do_arquivo, importada_por, linhas, novas, canceladas,
                       repetidas, ignoradas, recusadas
                  from importacao_de_extrato
                """ + (antesDe == null ? "" : " where (importada_em, id) < (:antesDe, :antesDoId)")
                + " order by importada_em desc, id desc limit :quantos";
        var comando = jdbc.sql(sql).param("quantos", quantos);
        if (antesDe != null) {
            comando = comando.param("antesDe", Timestamp.from(antesDe)).param("antesDoId", antesDoId);
        }
        return comando.query((rs, n) -> new Linha(rs.getObject("id", UUID.class),
                        rs.getObject("importada_em", OffsetDateTime.class).toInstant(),
                        rs.getString("nome_do_arquivo"), rs.getObject("importada_por", UUID.class),
                        rs.getInt("linhas"), rs.getInt("novas"), rs.getInt("canceladas"),
                        rs.getInt("repetidas"), rs.getInt("ignoradas"), rs.getInt("recusadas")))
                .list();
    }
}
