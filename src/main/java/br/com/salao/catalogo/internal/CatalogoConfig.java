package br.com.salao.catalogo.internal;

import br.com.salao.catalogo.api.ServicoApi;
import br.com.salao.catalogo.internal.application.CadastrarServicoUseCase;
import br.com.salao.catalogo.internal.application.DefinirAtivacaoDoServicoUseCase;
import br.com.salao.catalogo.internal.application.DefinirPrecoUseCase;
import br.com.salao.catalogo.internal.application.RemoverPrecoFuturoUseCase;
import br.com.salao.catalogo.internal.application.ListarServicosUseCase;
import br.com.salao.catalogo.internal.infra.ServicosJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.shared.tempo.Relogio;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/** RT-CAT-001 — fiação do módulo catalogo. */
@Configuration(proxyBeanMethods = false)
public class CatalogoConfig {

    @Bean
    public ServicosJdbc servicosJdbc(DataSource dataSource) {
        return new ServicosJdbc(JdbcClient.create(dataSource));
    }

    /** {@code @Primary} pela mesma razão de ClienteConfig e EquipeConfig — ver RT-CFG-001. */
    @Bean
    @Primary
    public ServicoApi servicoApi(ServicosJdbc jdbc) {
        return jdbc;
    }

    @Bean
    public CadastrarServicoUseCase cadastrarServicoUseCase(ServicosJdbc servicos,
                                                           AuditoriaApi auditoria) {
        return new CadastrarServicoUseCase(servicos, auditoria);
    }

    @Bean
    public DefinirPrecoUseCase definirPrecoUseCase(ServicosJdbc servicos,
                                                   EstabelecimentoApi estabelecimentos,
                                                   AuditoriaApi auditoria, Relogio relogio) {
        return new DefinirPrecoUseCase(servicos, estabelecimentos, auditoria, relogio);
    }

    @Bean
    public RemoverPrecoFuturoUseCase removerPrecoFuturoUseCase(
            ServicosJdbc servicos, EstabelecimentoApi estabelecimentos, AuditoriaApi auditoria,
            Relogio relogio) {
        return new RemoverPrecoFuturoUseCase(servicos, estabelecimentos, auditoria, relogio);
    }

    @Bean
    public ListarServicosUseCase listarServicosUseCase(ServicosJdbc servicos) {
        return new ListarServicosUseCase(servicos);
    }

    @Bean
    public DefinirAtivacaoDoServicoUseCase definirAtivacaoDoServicoUseCase(
            ServicosJdbc servicos, AuditoriaApi auditoria) {
        return new DefinirAtivacaoDoServicoUseCase(servicos, auditoria);
    }
}
