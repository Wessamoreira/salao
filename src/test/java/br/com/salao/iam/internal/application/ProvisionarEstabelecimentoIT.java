package br.com.salao.iam.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.salao.iam.api.BaseDeComissao;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** RT-IAM-001 — provisionar estabelecimento. */
class ProvisionarEstabelecimentoIT extends AbstractPostgresIT {

    @Autowired
    private ProvisionarEstabelecimentoUseCase provisionar;

    @Autowired
    private EstabelecimentoApi estabelecimentos;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager gerenciadorDeTransacao;

    private TransactionTemplate tx;

    @BeforeEach
    void prepararIam() {
        tx = new TransactionTemplate(gerenciadorDeTransacao);
        cacheManager.getCacheNames().forEach(n -> cacheManager.getCache(n).clear());
    }

    @Test
    @DisplayName("provisiona e o tenant fica imediatamente utilizável pela aplicação")
    void provisiona_e_o_tenant_fica_utilizavel() {
        UUID id = provisionar.executar(
                ProvisionarEstabelecimentoCommand.comPadroes("Salão da Ana", "12345678000199",
                        "Ana", "ana@salao.test", "senha-bem-comprida-1"));

        var configuracao = TenantContext.obter(id, () -> estabelecimentos.configuracao(id));

        assertThat(configuracao).isPresent().get().satisfies(c -> {
            assertThat(c.nome()).isEqualTo("Salão da Ana");
            assertThat(c.fuso()).isEqualTo(ZoneId.of("America/Sao_Paulo"));
            assertThat(c.moeda()).isEqualTo("BRL");
            assertThat(c.baseComissao()).isEqualTo(BaseDeComissao.BRUTO);
            assertThat(c.ativo()).isTrue();
        });
    }

    @Test
    @DisplayName("a aplicação não consegue criar estabelecimento, nem com tenant no escopo")
    void aplicacao_nao_cria_estabelecimento() {
        // Provisionar é operação de PLATAFORMA. Se salao_app conseguisse, qualquer falha de
        // autorização na aplicação viraria criação de tenant.
        UUID qualquer = UUID.randomUUID();

        assertThatThrownBy(() -> TenantContext.executar(qualquer, () ->
                tx.executeWithoutResult(s -> JdbcClient.create(dataSource)
                        .sql("insert into estabelecimento (id, nome) values (:id, 'Invasor')")
                        .param("id", qualquer)
                        .update())))
                .as("o grant de insert foi revogado em V6")
                .isNotNull();
    }

    @Test
    @DisplayName("configuração de outro estabelecimento não é visível")
    void configuracao_e_isolada_por_tenant() {
        UUID a = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes("Salão A", null, "Ana", "a@salao.test", "senha-bem-comprida-1"));
        UUID b = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes("Salão B", null, "Bia", "b@salao.test", "senha-bem-comprida-1"));

        var vistoPorA = TenantContext.obter(a, () -> estabelecimentos.configuracao(b));

        assertThat(vistoPorA)
                .as("a RLS filtra mesmo quando o id é conhecido")
                .isEmpty();
    }

    @Test
    @DisplayName("a segunda leitura vem do cache")
    void configuracao_e_cacheada() {
        UUID id = provisionar.executar(
                ProvisionarEstabelecimentoCommand.comPadroes("Nome Original", null, "Ana", "c@salao.test", "senha-bem-comprida-1"));

        TenantContext.obter(id, () -> estabelecimentos.configuracao(id));
        alterarNomeDireto(id, "Nome Alterado Por Fora");

        var segunda = TenantContext.obter(id, () -> estabelecimentos.configuracao(id));

        assertThat(segunda).get()
                .as("veio do cache; a invalidação é responsabilidade de quem altera")
                .extracting(c -> c.nome())
                .isEqualTo("Nome Original");
    }

    @Test
    @DisplayName("fuso inválido vira erro de domínio com código do catálogo")
    void fuso_invalido_e_erro_de_dominio() {
        var comando = new ProvisionarEstabelecimentoCommand(
                "Salão", null, "-03:00", "BRL", null, null, null,
                "Ana", "d@salao.test", "senha-bem-comprida-1");

        assertThatThrownBy(() -> provisionar.executar(comando))
                .isInstanceOf(ErroDeDominio.class)
                .extracting(e -> ((ErroDeDominio) e).codigo().codigo())
                .isEqualTo("ER-IAM-DADOS_INVALIDOS");
    }

    private void alterarNomeDireto(UUID id, String nome) {
        try (var ps = comoOwner().prepareStatement(
                "update estabelecimento set nome = ? where id = ?")) {
            ps.setString(1, nome);
            ps.setObject(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
