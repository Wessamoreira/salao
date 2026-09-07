package br.com.salao.financeiro.internal;

import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.catalogo.api.ServicoApi;
import br.com.salao.financeiro.api.RazaoApi;
import br.com.salao.financeiro.internal.application.CancelarValeUseCase;
import br.com.salao.financeiro.internal.application.CascataDeComissao;
import br.com.salao.financeiro.internal.application.Competencia;
import br.com.salao.financeiro.internal.application.ConsultarExtratoUseCase;
import br.com.salao.financeiro.internal.application.ListarValesUseCase;
import br.com.salao.financeiro.internal.application.RegistrarValeUseCase;
import br.com.salao.financeiro.internal.application.DefinirRegraDeComissaoUseCase;
import br.com.salao.financeiro.internal.application.ListarRegrasDeComissaoUseCase;
import br.com.salao.financeiro.internal.application.RemoverRegraDeComissaoUseCase;
import br.com.salao.financeiro.internal.application.Razao;
import br.com.salao.financeiro.internal.infra.LancamentosJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.financeiro.internal.application.Pagamentos;
import br.com.salao.financeiro.internal.infra.RegrasDeComissaoJdbc;
import br.com.salao.financeiro.internal.infra.PagamentosJdbc;
import br.com.salao.financeiro.internal.infra.ValesJdbc;
import br.com.salao.shared.idempotencia.Idempotencia;
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
    public LancamentosJdbc lancamentosJdbc(DataSource dataSource) {
        return new LancamentosJdbc(JdbcClient.create(dataSource));
    }

    @Bean
    public Competencia competencia(EstabelecimentoApi estabelecimentos, Relogio relogio) {
        return new Competencia(estabelecimentos, relogio);
    }

    @Bean
    public Razao razao(LancamentosJdbc lancamentos, Competencia competencia, Relogio relogio) {
        return new Razao(lancamentos, competencia, relogio);
    }

    @Bean
    public ValesJdbc valesJdbc(DataSource dataSource) {
        return new ValesJdbc(JdbcClient.create(dataSource));
    }

    @Bean
    public RegistrarValeUseCase registrarValeUseCase(ValesJdbc vales, LancamentosJdbc lancamentos,
                                                     ProfissionalApi profissionais,
                                                     Competencia competencia,
                                                     AuditoriaApi auditoria,
                                                     Idempotencia idempotencia, Relogio relogio) {
        return new RegistrarValeUseCase(vales, lancamentos, profissionais, competencia, auditoria,
                idempotencia, relogio);
    }

    @Bean
    public CancelarValeUseCase cancelarValeUseCase(ValesJdbc vales, RazaoApi razao,
                                                   AuditoriaApi auditoria) {
        return new CancelarValeUseCase(vales, razao, auditoria);
    }

    @Bean
    public ListarValesUseCase listarValesUseCase(ValesJdbc vales) {
        return new ListarValesUseCase(vales);
    }

    @Bean
    public ConsultarExtratoUseCase consultarExtratoUseCase(LancamentosJdbc lancamentos,
                                                           ProfissionalApi profissionais) {
        return new ConsultarExtratoUseCase(lancamentos, profissionais);
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
