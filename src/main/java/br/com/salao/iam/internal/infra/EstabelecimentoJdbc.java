package br.com.salao.iam.internal.infra;

import br.com.salao.iam.api.BaseDeComissao;
import br.com.salao.iam.api.ConfiguracaoDoEstabelecimento;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.EstabelecimentoNaoEncontradoException;
import br.com.salao.iam.api.PeriodicidadeDeFechamento;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-IAM-001 — leitura da configuração do estabelecimento.
 *
 * <p>Projeção direta por {@code JdbcClient}, sem entidade JPA: é leitura, e leitura não carrega
 * agregado. Entidade é para escrita.
 *
 * <p>{@code @Transactional} é <strong>obrigatório</strong>, mesmo sendo só leitura: é a transação
 * que dispara o {@code set_config('app.tenant_id')}. Fora dela, em autocommit, a RLS não encontra
 * o tenant e a consulta devolve zero linhas — falha fechada, mas confusa para quem investiga.
 */
public class EstabelecimentoJdbc implements EstabelecimentoApi {

    private static final String BUSCAR = """
            select id, nome, timezone, moeda, base_comissao,
                   desconto_afeta_comissao, periodicidade_fechamento, ativo
              from estabelecimento
             where id = :id
            """;

    private final JdbcClient jdbc;

    public EstabelecimentoJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConfiguracaoDoEstabelecimento> configuracao(UUID estabelecimentoId) {
        return jdbc.sql(BUSCAR)
                .param("id", estabelecimentoId)
                .query((rs, linha) -> new ConfiguracaoDoEstabelecimento(
                        rs.getObject("id", UUID.class),
                        rs.getString("nome"),
                        ZoneId.of(rs.getString("timezone")),
                        rs.getString("moeda"),
                        BaseDeComissao.valueOf(rs.getString("base_comissao")),
                        rs.getBoolean("desconto_afeta_comissao"),
                        PeriodicidadeDeFechamento.valueOf(rs.getString("periodicidade_fechamento")),
                        rs.getBoolean("ativo")))
                .optional();
    }

    @Override
    public ZoneId fusoDe(UUID estabelecimentoId) {
        return configuracao(estabelecimentoId)
                .map(ConfiguracaoDoEstabelecimento::fuso)
                .orElseThrow(() -> new EstabelecimentoNaoEncontradoException(estabelecimentoId));
    }
}
