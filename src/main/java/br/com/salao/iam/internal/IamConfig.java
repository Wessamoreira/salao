package br.com.salao.iam.internal;

import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.iam.internal.infra.EstabelecimentoCacheado;
import br.com.salao.iam.internal.infra.EstabelecimentoJdbc;
import br.com.salao.shared.manutencao.ConexaoDeManutencao;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/** RT-IAM-001 — fiação do módulo iam. */
@Configuration(proxyBeanMethods = false)
public class IamConfig {

    @Bean
    public EstabelecimentoJdbc estabelecimentoJdbc(DataSource dataSource) {
        return new EstabelecimentoJdbc(JdbcClient.create(dataSource));
    }

    /** O que os outros módulos recebem ao injetar {@link EstabelecimentoApi}. */
    @Bean
    @Primary
    public EstabelecimentoApi estabelecimentoApi(EstabelecimentoJdbc jdbc) {
        return new EstabelecimentoCacheado(jdbc);
    }

    @Bean
    public ProvisionarEstabelecimentoUseCase provisionarEstabelecimentoUseCase(
            ConexaoDeManutencao plataforma) {
        return new ProvisionarEstabelecimentoUseCase(plataforma);
    }
}
