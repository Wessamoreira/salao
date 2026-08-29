package br.com.salao.shared.observabilidade;

import br.com.salao.shared.tenant.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * RT-INF-006 / RT-INF-008 — leva o contexto da thread que publica para a que consome.
 *
 * <p>Duas coisas atravessam: o <strong>tenant</strong>, sem o qual a transação do listener é
 * recusada, e o <strong>MDC</strong>, sem o qual o log do trabalho assíncrono perde
 * {@code traceId} e {@code tenantId} — e deixa de ser possível ligar "a confirmação não saiu" ao
 * clique que a originou. O MDC é {@code ThreadLocal} e some na fronteira de thread exatamente
 * como o {@code ScopedValue}.
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
public class PropagadorDeContexto implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable tarefa) {
        UUID tenant = TenantContext.atual();
        Map<String, String> contextoDeLog = MDC.getCopyOfContextMap();

        return () -> {
            Map<String, String> anterior = MDC.getCopyOfContextMap();
            if (contextoDeLog != null) {
                MDC.setContextMap(contextoDeLog);
            }
            try {
                if (tenant == null) {
                    tarefa.run();
                } else {
                    TenantContext.executar(tenant, tarefa);
                }
            } finally {
                // A thread trabalhadora é reutilizada: deixar o MDC sujo faria o próximo
                // trabalho logar o traceId do anterior, que é pior que não logar nenhum.
                if (anterior == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(anterior);
                }
            }
        };
    }
}
