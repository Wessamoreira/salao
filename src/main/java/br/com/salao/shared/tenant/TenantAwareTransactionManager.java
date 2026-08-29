package br.com.salao.shared.tenant;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * RT-INF-002 — a ponte entre a camada 1 (aplicação) e a camada 2 (RLS no banco).
 *
 * <p>Emite {@code set_config('app.tenant_id', ?, true)} no início de <strong>toda</strong>
 * transação. O terceiro argumento {@code true} significa {@code LOCAL}: o valor morre junto com a
 * transação e a conexão volta limpa ao pool.
 *
 * <p><strong>Por que aqui e não num aspecto:</strong> um {@code @Aspect} precisaria rodar
 * <em>dentro</em> da transação, o que exige reordenar o {@code TransactionInterceptor} do Spring —
 * frágil e fácil de quebrar sem ninguém perceber. Sobrescrever {@code doBegin} engancha
 * exatamente no início da transação, para todas elas, sem depender de ordem de advice.
 *
 * <p><strong>Por que {@code SET LOCAL} e não {@code SET}:</strong> com {@code SET}, o tenant fica
 * grudado na conexão; quando ela volta ao pool, a próxima requisição — de outro estabelecimento —
 * a recebe com o tenant anterior. O bug funciona perfeitamente em desenvolvimento com um usuário
 * só e vaza em produção sob concorrência. É o cenário do teste
 * {@code conexao_reusada_do_pool_nao_herda_tenant_anterior}.
 */
public class TenantAwareTransactionManager extends JpaTransactionManager {

    private static final String SQL = "select set_config('app.tenant_id', :tenant, true)";

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);

        UUID tenant = TenantContext.atual();
        if (tenant == null && !TenantContext.semTenantPermitido()) {
            throw new TenantNaoDefinidoException();   // RN-INF-003
        }

        // Sem tenant: define string vazia. A política usa nullif(...,'')::uuid, que vira NULL,
        // e a comparação resulta em NULL — nenhuma linha passa. Falha fechada.
        entityManager().createNativeQuery(SQL)
                .setParameter("tenant", tenant == null ? "" : tenant.toString())
                .getSingleResult();
    }

    private EntityManager entityManager() {
        var holder = (EntityManagerHolder) TransactionSynchronizationManager
                .getResource(obterEntityManagerFactory());
        if (holder == null) {
            throw new IllegalStateException(
                    "Nenhum EntityManager ligado à transação ao aplicar o tenant.");
        }
        return holder.getEntityManager();
    }

    private jakarta.persistence.EntityManagerFactory obterEntityManagerFactory() {
        var emf = getEntityManagerFactory();
        if (emf == null) {
            throw new IllegalStateException("EntityManagerFactory não configurada.");
        }
        return emf;
    }
}
