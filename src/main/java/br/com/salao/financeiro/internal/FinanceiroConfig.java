package br.com.salao.financeiro.internal;

import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.catalogo.api.ServicoApi;
import br.com.salao.financeiro.internal.application.CascataDeComissao;
import br.com.salao.financeiro.internal.application.DefinirRegraDeComissaoUseCase;
import br.com.salao.financeiro.internal.application.ListarRegrasDeComissaoUseCase;
import br.com.salao.financeiro.internal.application.RemoverRegraDeComissaoUseCase;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.financeiro.internal.application.Pagamentos;
import br.com.salao.financeiro.internal.infra.RegrasDeComissaoJdbc;
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

    @Bean
    public RegrasDeComissaoJdbc regrasDeComissaoJdbc(DataSource dataSource) {
        return new RegrasDeComissaoJdbc(JdbcClient.create(dataSource));
    }

    @Bean
    public CascataDeComissao cascataDeComissao(RegrasDeComissaoJdbc regras,
                                               ProfissionalApi profissionais) {
        return new CascataDeComissao(regras, profissionais);
    }

    @Bean
    public DefinirRegraDeComissaoUseCase definirRegraDeComissaoUseCase(
            RegrasDeComissaoJdbc regras, ProfissionalApi profissionais, ServicoApi servicos,
            AuditoriaApi auditoria, Relogio relogio) {
        return new DefinirRegraDeComissaoUseCase(regras, profissionais, servicos, auditoria,
                relogio);
    }

    @Bean
    public RemoverRegraDeComissaoUseCase removerRegraDeComissaoUseCase(
            RegrasDeComissaoJdbc regras, AuditoriaApi auditoria) {
        return new RemoverRegraDeComissaoUseCase(regras, auditoria);
    }

    @Bean
    public ListarRegrasDeComissaoUseCase listarRegrasDeComissaoUseCase(
            RegrasDeComissaoJdbc regras) {
        return new ListarRegrasDeComissaoUseCase(regras);
    }
}
