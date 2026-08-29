package br.com.salao.shared.tenant;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * RT-INF-002 — Camada 1 do isolamento: o tenant da requisição em curso.
 *
 * <p>Usa {@link ScopedValue} e não {@code ThreadLocal}: com virtual threads ligadas
 * ({@code spring.threads.virtual.enabled=true}) o número de carriers é alto e
 * {@code ThreadLocal} pesado é um dos poucos problemas que sobraram depois do JEP 491.
 *
 * <p>O valor é imutável dentro do escopo e desaparece sozinho ao fim dele — não existe
 * {@code limpar()} para alguém esquecer de chamar.
 */
public final class TenantContext {

    private static final ScopedValue<UUID> TENANT = ScopedValue.newInstance();
    private static final ScopedValue<Boolean> SEM_TENANT = ScopedValue.newInstance();

    private TenantContext() {
    }

    /** Executa a ação no escopo do tenant informado. */
    public static void executar(UUID estabelecimentoId, Runnable acao) {
        if (estabelecimentoId == null) {
            throw new IllegalArgumentException("estabelecimentoId não pode ser nulo");
        }
        ScopedValue.where(TENANT, estabelecimentoId).run(acao);
    }

    /** Idem, devolvendo um valor. */
    public static <T> T executar(UUID estabelecimentoId, Supplier<T> acao) {
        var resultado = new Object[1];
        executar(estabelecimentoId, () -> resultado[0] = acao.get());
        @SuppressWarnings("unchecked")
        T valor = (T) resultado[0];
        return valor;
    }

    /**
     * Escape hatch explícito para operações que legitimamente não têm tenant: healthcheck,
     * listener de LISTEN/NOTIFY, migration.
     *
     * <p>Não é um bypass do isolamento. A transação segue com {@code app.tenant_id} vazio, e a
     * política de RLS filtra <em>tudo</em> — falha fechada. Se uma dessas operações tocar tabela
     * de negócio, ela lê zero linhas, e é isso mesmo que se quer.
     */
    public static void executarSemTenant(Runnable acao) {
        ScopedValue.where(SEM_TENANT, Boolean.TRUE).run(acao);
    }

    /** Idem, devolvendo um valor. */
    public static <T> T executarSemTenant(Supplier<T> acao) {
        var resultado = new Object[1];
        executarSemTenant(() -> resultado[0] = acao.get());
        @SuppressWarnings("unchecked")
        T valor = (T) resultado[0];
        return valor;
    }

    /** O tenant atual, ou {@code null} se não houver escopo aberto. */
    public static UUID atual() {
        return TENANT.isBound() ? TENANT.get() : null;
    }

    /** O tenant atual; estoura se não houver. Use quando a ausência é bug, não fluxo. */
    public static UUID obrigatorio() {
        UUID atual = atual();
        if (atual == null) {
            throw new TenantNaoDefinidoException();
        }
        return atual;
    }

    /** Verdadeiro apenas dentro de {@link #executarSemTenant(Runnable)}. */
    public static boolean semTenantPermitido() {
        return SEM_TENANT.isBound() && Boolean.TRUE.equals(SEM_TENANT.get());
    }
}
