package br.com.salao.shared.tenant;

/**
 * RT-INF-002 / RN-INF-003 — transação aberta sem tenant no escopo.
 *
 * <p>Isto é <strong>bug</strong>, nunca erro de usuário: significa que um caso de uso rodou fora
 * do escopo de uma requisição autenticada e sem declarar
 * {@link TenantContext#executarSemTenant(Runnable)}. Falhar aqui é deliberado — assumir um tenant
 * padrão é exatamente como vazamento entre estabelecimentos nasce.
 */
public class TenantNaoDefinidoException extends IllegalStateException {

    public TenantNaoDefinidoException() {
        super("Transação iniciada sem tenant no escopo. "
                + "Use TenantContext.executar(...) ou, se a operação realmente não tem tenant, "
                + "TenantContext.executarSemTenant(...).");
    }
}
