package br.com.salao.shared.observabilidade;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.shared.tenant.TenantContext;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class PropagadorDeContextoTest {

    private final PropagadorDeContexto propagador = new PropagadorDeContexto();

    @AfterEach
    void limparMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("tenant e MDC atravessam a fronteira de thread")
    void propaga_tenant_e_mdc() throws Exception {
        UUID tenant = UUID.randomUUID();
        var tenantVisto = new AtomicReference<UUID>();
        var traceVisto = new AtomicReference<String>();

        Runnable decorada = TenantContext.obter(tenant, () -> {
            MDC.put("traceId", "abc123");
            return propagador.decorate(() -> {
                tenantVisto.set(TenantContext.atual());
                traceVisto.set(MDC.get("traceId"));
            });
        });

        var thread = new Thread(decorada);
        thread.start();
        thread.join();

        assertThat(tenantVisto.get()).isEqualTo(tenant);
        assertThat(traceVisto.get())
                .as("sem o MDC, o log do trabalho assíncrono perde o vínculo com o clique")
                .isEqualTo("abc123");
    }

    @Test
    @DisplayName("a thread trabalhadora fica limpa depois, para não vazar contexto no próximo uso")
    void limpa_o_mdc_ao_terminar() throws Exception {
        // Thread de pool é reutilizada: MDC sujo faria o próximo trabalho logar o traceId do
        // anterior, que é pior que não logar nenhum.
        UUID tenant = UUID.randomUUID();
        var sobrou = new AtomicReference<String>();

        Runnable decorada = TenantContext.obter(tenant, () -> {
            MDC.put("traceId", "abc123");
            return propagador.decorate(() -> { });
        });

        var thread = new Thread(() -> {
            decorada.run();
            sobrou.set(MDC.get("traceId"));
        });
        thread.start();
        thread.join();

        assertThat(sobrou.get()).isNull();
    }

    @Test
    @DisplayName("sem tenant na origem, não inventa um")
    void sem_tenant_executa_intacta() throws Exception {
        var executou = new AtomicReference<Boolean>(false);

        var thread = new Thread(propagador.decorate(() -> executou.set(true)));
        thread.start();
        thread.join();

        assertThat(executou.get()).isTrue();
    }
}
