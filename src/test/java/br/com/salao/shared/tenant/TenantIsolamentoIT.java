package br.com.salao.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * RT-INF-002 — o entregável real da rotina.
 *
 * <p>Estes testes são o que transforma a decisão D1 de intenção em garantia. Sem eles, o
 * isolamento funciona no dia em que foi escrito e fura, silenciosamente, em algum ponto dos seis
 * meses seguintes que ninguém consegue apontar.
 */
class TenantIsolamentoIT extends AbstractPostgresIT {

    /**
     * Pool de 1: força transações sequenciais a compartilharem fisicamente a mesma conexão, que é
     * a única forma determinística de provocar o vazamento por {@code SET} sem {@code LOCAL}.
     * Declarado aqui, e não na base, porque outros testes precisam de concorrência real.
     */
    @DynamicPropertySource
    static void poolDeUmaConexao(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
    }

    @Autowired
    private PlatformTransactionManager gerenciadorDeTransacao;

    @PersistenceContext
    private EntityManager em;

    private TransactionTemplate tx;

    @BeforeEach
    void prepararTransacao() {
        tx = new TransactionTemplate(gerenciadorDeTransacao);
    }

    private long contarAuditoria() {
        return ((Number) em.createNativeQuery("select count(*) from auditoria")
                .getSingleResult()).longValue();
    }

    @Test
    @DisplayName("usuário do tenant A não lê auditoria do tenant B")
    void usuario_do_tenant_a_nao_le_auditoria_do_tenant_b() throws SQLException {
        UUID a = criarEstabelecimento("Salão A");
        UUID b = criarEstabelecimento("Salão B");
        criarAuditorias(a, 3);
        criarAuditorias(b, 5);

        long vistoPorA = TenantContext.obter(a, () -> tx.execute(s -> contarAuditoria()));

        assertThat(vistoPorA)
                .as("A tem 3 registros; existem 8 no total")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("query sem tenant retorna zero linhas, e não todas")
    void query_sem_tenant_retorna_zero_linhas_e_nao_todas() throws SQLException {
        // O teste mais importante do projeto: prova que a falha é FECHADA.
        // Se a política estivesse errada, o modo de falha seria devolver tudo — que é
        // exatamente o vazamento que a RLS existe para impedir.
        UUID a = criarEstabelecimento("Salão A");
        criarAuditorias(a, 4);

        long visto = TenantContext.obterSemTenant(() -> tx.execute(s -> contarAuditoria()));

        assertThat(visto).isZero();
    }

    @Test
    @DisplayName("conexão reusada do pool não herda o tenant anterior")
    void conexao_reusada_do_pool_nao_herda_tenant_anterior() throws SQLException {
        // Pool de tamanho 1 (ver AbstractPostgresIT): as duas transações usam FISICAMENTE
        // a mesma conexão. Com 'SET' em vez de 'SET LOCAL', a segunda leitura devolveria 3.
        // Esse bug funciona perfeitamente em dev com um usuário só e vaza em produção sob
        // concorrência — é a razão de este teste existir.
        UUID a = criarEstabelecimento("Salão A");
        UUID b = criarEstabelecimento("Salão B");
        criarAuditorias(a, 3);
        criarAuditorias(b, 5);

        long vistoPorA = TenantContext.obter(a, () -> tx.execute(s -> contarAuditoria()));
        long vistoPorB = TenantContext.obter(b, () -> tx.execute(s -> contarAuditoria()));
        long vistoPorAdeNovo = TenantContext.obter(a, () -> tx.execute(s -> contarAuditoria()));

        assertThat(vistoPorA).isEqualTo(3);
        assertThat(vistoPorB).isEqualTo(5);
        assertThat(vistoPorAdeNovo).isEqualTo(3);
    }

    @Test
    @DisplayName("transação sem escopo de tenant falha em vez de assumir um padrão")
    void transacao_sem_escopo_falha() {
        // RN-INF-003. Assumir um tenant padrão é exatamente como vazamento nasce.
        assertThatThrownBy(() -> tx.execute(s -> contarAuditoria()))
                .isInstanceOf(TenantNaoDefinidoException.class);
    }

    @Test
    @DisplayName("insert com estabelecimento_id fora do escopo é bloqueado pelo WITH CHECK")
    void insert_com_tenant_alheio_e_bloqueado() throws SQLException {
        UUID a = criarEstabelecimento("Salão A");
        UUID b = criarEstabelecimento("Salão B");

        assertThatThrownBy(() -> TenantContext.obter(a, () -> tx.execute(s ->
                em.createNativeQuery("""
                                insert into auditoria (estabelecimento_id, ator, acao, entidade)
                                values (:outro, 'SISTEMA', 'TESTE', 'teste')
                                """)
                        .setParameter("outro", b)
                        .executeUpdate())))
                .as("a política não protege só a leitura")
                .isNotNull();
    }

    @Test
    @DisplayName("a aplicação não é dona das tabelas (RN-INF-004)")
    void aplicacao_nao_e_dona_das_tabelas() {
        // Sem isto, o Postgres ignora a RLS para o owner e todos os testes acima
        // passariam sem provar nada.
        String dono = TenantContext.obterSemTenant(() -> tx.execute(s ->
                (String) em.createNativeQuery(
                                "select tableowner from pg_tables where tablename = 'auditoria'")
                        .getSingleResult()));

        assertThat(dono).isEqualTo(OWNER).isNotEqualTo("salao_app");
    }
}
