package br.com.salao.shared.idempotencia;

import br.com.salao.shared.tempo.Relogio;
import java.time.Duration;
import javax.sql.DataSource;
import br.com.salao.shared.manutencao.ConexaoDeManutencao;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** RT-INF-005 — fiação. */
@Configuration(proxyBeanMethods = false)
public class IdempotenciaConfig {

    /**
     * Usa o {@code DataSource} da aplicação — e é obrigatório que use. O registro precisa
     * participar da mesma transação do efeito de negócio; num {@code DataSource} próprio ele
     * commitaria à parte, que é exatamente a janela que este desenho elimina.
     */
    @Bean
    public Idempotencia idempotencia(DataSource dataSource, ObjectMapper json, Relogio relogio,
                                     @Value("${app.idempotencia.retencao:P7D}") Duration retencao) {
        return new IdempotenciaJdbc(JdbcClient.create(dataSource), json, relogio, retencao);
    }

    @Bean
    public PurgadorDeIdempotencia purgadorDeIdempotencia(ConexaoDeManutencao manutencao) {
        return new PurgadorDeIdempotencia(manutencao);
    }
}
