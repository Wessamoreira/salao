package br.com.salao.shared.evento;

import br.com.salao.shared.manutencao.ConexaoDeManutencao;
import br.com.salao.shared.observabilidade.PropagadorDeContexto;
import br.com.salao.shared.tempo.Relogio;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.modulith.events.IncompleteEventPublications;

/** RT-INF-006 — fiação do outbox. */
@Configuration(proxyBeanMethods = false)
public class EventoConfig {

    /**
     * O Boot aplica um único bean {@link TaskDecorator} ao executor de {@code @Async}. É por aqui
     * que o tenant e o MDC atravessam a fronteira de thread — sem isto, todo listener assíncrono
     * falha ao abrir transação, e o log do trabalho assíncrono perde o {@code traceId}.
     */
    @Bean
    public TaskDecorator propagadorDeContexto() {
        return new PropagadorDeContexto();
    }

    @Bean
    public ReenviadorDeEventos reenviadorDeEventos(
            IncompleteEventPublications incompletas,
            ConexaoDeManutencao manutencao,
            Relogio relogio,
            @Value("${app.outbox.idade-minima-para-reenvio:PT5M}") Duration idadeMinima) {
        return new ReenviadorDeEventos(incompletas, manutencao, relogio, idadeMinima);
    }

    @Bean
    public PurgadorDoOutbox purgadorDoOutbox(
            ConexaoDeManutencao manutencao,
            @Value("${app.outbox.retencao-arquivo:P14D}") Duration retencao) {
        return new PurgadorDoOutbox(manutencao, retencao);
    }

    @Bean
    public MetricasDoOutbox metricasDoOutbox(ConexaoDeManutencao manutencao) {
        return new MetricasDoOutbox(manutencao);
    }
}
