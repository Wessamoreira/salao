package br.com.salao.iam.internal.infra;

import br.com.salao.iam.api.BaseDeComissao;
import br.com.salao.iam.api.ConfiguracaoDoEstabelecimento;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.EstabelecimentoNaoEncontradoException;
import br.com.salao.iam.api.PeriodicidadeDeFechamento;
import br.com.salao.iam.internal.domain.DadosDoEstabelecimento;
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
                   desconto_afeta_comissao, periodicidade_fechamento,
                   pais_padrao, ddd_padrao, granularidade_agenda_minutos, ativo
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
                        rs.getString("pais_padrao"),
                        rs.getString("ddd_padrao"),
                        rs.getInt("granularidade_agenda_minutos"),
                        rs.getBoolean("ativo")))
                .optional();
    }

    /**
     * RT-CFG-001 — grava a configuração alterada.
     *
     * <p>Não recebe {@code id}: a RLS já limita a linha visível ao tenant da transação, e passar o
     * id daria a impressão de que um estabelecimento pode alterar outro. O {@code where} sem
     * filtro parece ousado e é o contrário — é a política do banco fazendo o trabalho.
     */
    @Transactional
    public void atualizar(DadosDoEstabelecimento dados) {
        jdbc.sql("""
                update estabelecimento
                   set nome = :nome,
                       documento = :documento,
                       timezone = :fuso,
                       moeda = :moeda,
                       base_comissao = :base,
                       desconto_afeta_comissao = :descontoAfeta,
                       periodicidade_fechamento = :periodicidade,
                       ddd_padrao = :dddPadrao
                """)
                .param("nome", dados.nome())
                .param("documento", dados.documento())
                .param("fuso", dados.fuso().getId())
                .param("moeda", dados.moeda())
                .param("base", dados.baseComissao().name())
                .param("descontoAfeta", dados.descontoAfetaComissao())
                .param("periodicidade", dados.periodicidadeDeFechamento().name())
                .param("dddPadrao", dados.dddPadrao())
                .update();
    }

    /**
     * {@code @Transactional} aqui não é decoração: sem ele, a chamada a {@code configuracao} logo
     * abaixo é <strong>auto-invocação</strong> e não passa pelo proxy, então nenhuma transação é
     * aberta — e sem transação não há {@code SET LOCAL app.tenant_id}, o que faz a RLS esconder a
     * linha e este método estourar "estabelecimento não encontrado" para um tenant que existe.
     *
     * <p>Funcionou por muito tempo porque todo chamador já vinha de dentro de uma transação. O
     * primeiro que não vinha (RT-AGD-009, que precisa de uma transação por ocorrência) revelou a
     * dependência escondida.
     */
    @Override
    @Transactional(readOnly = true)
    public ZoneId fusoDe(UUID estabelecimentoId) {
        return configuracao(estabelecimentoId)
                .map(ConfiguracaoDoEstabelecimento::fuso)
                .orElseThrow(() -> new EstabelecimentoNaoEncontradoException(estabelecimentoId));
    }
}
