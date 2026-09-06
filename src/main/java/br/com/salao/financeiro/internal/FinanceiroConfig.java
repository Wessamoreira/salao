package br.com.salao.financeiro.internal;

import br.com.salao.financeiro.internal.application.Pagamentos;
import br.com.salao.financeiro.internal.infra.PagamentosJdbc;
import br.com.salao.shared.tempo.Relogio;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** RT-FIN-001 — fiação do módulo financeiro. */
@Configuration(proxyBeanMethods = false)
public class FinanceiroConfig {

    @Bean
    public PagamentosJdbc pagamentosJdbc(DataSource dataSource) {
        return new PagamentosJdbc(JdbcClient.create(dataSource));
    }

    @Bean
    public Pagamentos pagamentos(PagamentosJdbc pagamentos, Relogio relogio) {
        return new Pagamentos(pagamentos, relogio);
    }
}
