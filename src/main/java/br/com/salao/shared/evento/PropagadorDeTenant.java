package br.com.salao.shared.evento;

import br.com.salao.shared.tenant.TenantContext;
import java.util.UUID;
import org.springframework.core.task.TaskDecorator;

/**
 * RT-INF-006 — leva o tenant da thread que publica para a que consome.
 *
 * <p>{@code @ApplicationModuleListener} é {@code @Async} + {@code @Transactional}: a transação
 * abre <em>antes</em> do corpo do método, e {@code TenantAwareTransactionManager} exige o tenant
 * nesse instante. Como {@link ScopedValue} não atravessa threads, sem isto todo listener
 * assíncrono falharia com {@code TenantNaoDefinidoException}.
 *
 * <p><strong>Por que um {@code TaskDecorator} e não um {@code @Aspect}:</strong> um aspecto
 * precisaria rodar depois do despacho do {@code @Async} e antes do {@code @Transactional} — e as
 * duas advices usam {@code LOWEST_PRECEDENCE}, então a ordem entre elas não é algo em que se possa
 * confiar. O decorator envolve o {@code Runnable} no momento da submissão e o desembrulha já na
 * thread trabalhadora: sem disputa de ordem, sem depender de detalhe interno do Spring.
 *
 * <p>Sem tenant na origem, devolve a tarefa intacta em vez de inventar um. Isso acontece no
 * reenvio de pendências, e ali quem define o escopo é o {@link ReenviadorDeEventos}.
 */
public class PropagadorDeTenant implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable tarefa) {
        UUID tenant = TenantContext.atual();
        if (tenant == null) {
            return tarefa;
        }
        return () -> TenantContext.executar(tenant, tarefa);
    }
}
