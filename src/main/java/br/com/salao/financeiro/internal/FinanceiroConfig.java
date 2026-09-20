package br.com.salao.financeiro.internal;

import br.com.salao.catalogo.api.ServicoApi;
import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.financeiro.api.RazaoApi;
import br.com.salao.financeiro.internal.application.Anulacao;
import br.com.salao.financeiro.internal.application.CancelarValeUseCase;
import br.com.salao.financeiro.internal.application.CascataDeComissao;
import br.com.salao.financeiro.internal.application.Competencia;
import br.com.salao.financeiro.internal.application.Conciliador;
import br.com.salao.financeiro.internal.application.ConciliarUseCase;
import br.com.salao.financeiro.internal.application.ConsultarConciliacaoUseCase;
import br.com.salao.financeiro.internal.application.ConsultarExtratoUseCase;
import br.com.salao.financeiro.internal.application.ContasDoPeriodo;
import br.com.salao.financeiro.internal.application.DefinirRegraDeComissaoUseCase;
import br.com.salao.financeiro.internal.application.EstornarFechamentoUseCase;
import br.com.salao.financeiro.internal.application.EstornarLancamentoUseCase;
import br.com.salao.financeiro.internal.application.FecharPeriodoUseCase;
import br.com.salao.financeiro.internal.application.ImportarExtratoUseCase;
import br.com.salao.financeiro.internal.application.ListarFechamentosUseCase;
import br.com.salao.financeiro.internal.application.ListarImportacoesUseCase;
import br.com.salao.financeiro.internal.application.ListarRegrasDeComissaoUseCase;
import br.com.salao.financeiro.internal.application.ListarValesUseCase;
import br.com.salao.financeiro.internal.application.Pagamentos;
import br.com.salao.financeiro.internal.application.PeriodoDeFechamento;
import br.com.salao.financeiro.internal.application.ConsultarTaxasDoAdquirenteUseCase;
import br.com.salao.financeiro.internal.application.DefinirTaxasDoAdquirenteUseCase;
import br.com.salao.financeiro.internal.application.PreverFechamentoUseCase;
import br.com.salao.financeiro.internal.application.PreverRecebiveisUseCase;
import br.com.salao.financeiro.internal.application.PrevisaoDeRecebimento;
import br.com.salao.financeiro.internal.application.TaxaNoRazao;
import br.com.salao.financeiro.internal.application.PreverImportacaoUseCase;
import br.com.salao.financeiro.internal.application.Razao;
import br.com.salao.financeiro.internal.application.RegistrarValeUseCase;
import br.com.salao.financeiro.internal.application.RemoverRegraDeComissaoUseCase;
import br.com.salao.financeiro.internal.application.ResolverDivergenciaUseCase;
import br.com.salao.financeiro.internal.infra.ConciliacaoJdbc;
import br.com.salao.financeiro.internal.infra.FechamentosJdbc;
import br.com.salao.financeiro.internal.infra.ImportacoesDeExtratoJdbc;
import br.com.salao.financeiro.internal.infra.LancamentosJdbc;
import br.com.salao.financeiro.internal.infra.MapeamentosDeExtratoJdbc;
import br.com.salao.financeiro.internal.infra.PagamentosJdbc;
import br.com.salao.financeiro.internal.infra.RegrasDeComissaoJdbc;
import br.com.salao.financeiro.internal.infra.ResolucoesJdbc;
import br.com.salao.financeiro.internal.infra.TaxasDoAdquirenteJdbc;
import br.com.salao.financeiro.internal.infra.TransacoesDoAdquirenteJdbc;
import br.com.salao.financeiro.internal.infra.ValesJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.NomesDeUsuariosApi;
import br.com.salao.shared.idempotencia.Idempotencia;
import br.com.salao.shared.tempo.Relogio;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** RT-FIN-001 — fiação do módulo financeiro. */
@Configuration(proxyBeanMethods = false)
public class FinanceiroConfig {

    @Bean
    public PagamentosJdbc pagamentosJdbc(DataSource dataSource) {
        return new PagamentosJdbc(JdbcClient.create(dataSource));
    }

    @Bean
    public Pagamentos pagamentos(PagamentosJdbc pagamentos, PrevisaoDeRecebimento previsao,
                                 Relogio relogio) {
        return new Pagamentos(pagamentos, previsao, relogio);
    }

    // ------------------------------------------------------------------ RT-FIN-012 — taxa e prazo

    @Bean
    public TaxasDoAdquirenteJdbc taxasDoAdquirenteJdbc(DataSource dataSource) {
        return new TaxasDoAdquirenteJdbc(JdbcClient.create(dataSource));
    }

    @Bean
    public PrevisaoDeRecebimento previsaoDeRecebimento(TaxasDoAdquirenteJdbc taxas,
                                                       Competencia competencia) {
        return new PrevisaoDeRecebimento(taxas, competencia);
    }

    @Bean
    public DefinirTaxasDoAdquirenteUseCase definirTaxasDoAdquirenteUseCase(
            TaxasDoAdquirenteJdbc taxas, AuditoriaApi auditoria, Relogio relogio) {
        return new DefinirTaxasDoAdquirenteUseCase(taxas, auditoria, relogio);
    }

    @Bean
    public ConsultarTaxasDoAdquirenteUseCase consultarTaxasDoAdquirenteUseCase(
            TaxasDoAdquirenteJdbc taxas) {
        return new ConsultarTaxasDoAdquirenteUseCase(taxas);
    }

    @Bean
    public PreverRecebiveisUseCase preverRecebiveisUseCase(PagamentosJdbc pagamentos,
                                                           EstabelecimentoApi estabelecimentos,
                                                           Relogio relogio) {
        return new PreverRecebiveisUseCase(pagamentos, estabelecimentos, relogio);
    }

    @Bean
    public TaxaNoRazao taxaNoRazao(TransacoesDoAdquirenteJdbc transacoes,
                                   LancamentosJdbc lancamentos, RazaoApi razao,
                                   EstabelecimentoApi estabelecimentos) {
        return new TaxaNoRazao(transacoes, lancamentos, razao, estabelecimentos);
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
    public FechamentosJdbc fechamentosJdbc(DataSource dataSource) {
        return new FechamentosJdbc(JdbcClient.create(dataSource));
    }

    @Bean
    public ContasDoPeriodo contasDoPeriodo(LancamentosJdbc lancamentos) {
        return new ContasDoPeriodo(lancamentos);
    }

    @Bean
    public PeriodoDeFechamento periodoDeFechamento(FechamentosJdbc fechamentos,
                                                   Competencia competencia) {
        return new PeriodoDeFechamento(fechamentos, competencia);
    }

    @Bean
    public PreverFechamentoUseCase preverFechamentoUseCase(ContasDoPeriodo contas,
                                                           PeriodoDeFechamento periodo) {
        return new PreverFechamentoUseCase(contas, periodo);
    }

    @Bean
    public FecharPeriodoUseCase fecharPeriodoUseCase(FechamentosJdbc fechamentos,
                                                     LancamentosJdbc lancamentos,
                                                     ContasDoPeriodo contas,
                                                     PeriodoDeFechamento periodo,
                                                     AuditoriaApi auditoria, Relogio relogio) {
        return new FecharPeriodoUseCase(fechamentos, lancamentos, contas, periodo, auditoria,
                relogio);
    }

    @Bean
    public Anulacao anulacao(LancamentosJdbc lancamentos, Relogio relogio) {
        return new Anulacao(lancamentos, relogio);
    }

    @Bean
    public EstornarFechamentoUseCase estornarFechamentoUseCase(FechamentosJdbc fechamentos,
                                                               Anulacao anulacao,
                                                               AuditoriaApi auditoria,
                                                               Relogio relogio) {
        return new EstornarFechamentoUseCase(fechamentos, anulacao, auditoria, relogio);
    }

    @Bean
    public EstornarLancamentoUseCase estornarLancamentoUseCase(LancamentosJdbc lancamentos,
                                                               Anulacao anulacao,
                                                               AuditoriaApi auditoria) {
        return new EstornarLancamentoUseCase(lancamentos, anulacao, auditoria);
    }

    @Bean
    public ListarFechamentosUseCase listarFechamentosUseCase(FechamentosJdbc fechamentos) {
        return new ListarFechamentosUseCase(fechamentos);
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

    // ------------------------------------------------------------------ RT-FIN-009 — extrato

    @Bean
    public TransacoesDoAdquirenteJdbc transacoesDoAdquirenteJdbc(DataSource dataSource) {
        return new TransacoesDoAdquirenteJdbc(JdbcClient.create(dataSource));
    }

    @Bean
    public ImportacoesDeExtratoJdbc importacoesDeExtratoJdbc(DataSource dataSource,
                                                             ObjectMapper json) {
        return new ImportacoesDeExtratoJdbc(JdbcClient.create(dataSource), json);
    }

    @Bean
    public MapeamentosDeExtratoJdbc mapeamentosDeExtratoJdbc(DataSource dataSource,
                                                             ObjectMapper json) {
        return new MapeamentosDeExtratoJdbc(JdbcClient.create(dataSource), json);
    }

    @Bean
    public PreverImportacaoUseCase preverImportacaoUseCase(MapeamentosDeExtratoJdbc mapeamentos,
                                                           ImportacoesDeExtratoJdbc importacoes) {
        return new PreverImportacaoUseCase(mapeamentos, importacoes);
    }

    @Bean
    public ImportarExtratoUseCase importarExtratoUseCase(TransacoesDoAdquirenteJdbc transacoes,
                                                         ImportacoesDeExtratoJdbc importacoes,
                                                         MapeamentosDeExtratoJdbc mapeamentos,
                                                         EstabelecimentoApi estabelecimentos,
                                                         AuditoriaApi auditoria,
                                                         Conciliador conciliador,
                                                         TaxaNoRazao taxaNoRazao, Relogio relogio) {
        return new ImportarExtratoUseCase(transacoes, importacoes, mapeamentos, estabelecimentos,
                auditoria, conciliador, taxaNoRazao, relogio);
    }

    // ------------------------------------------------------------------ RT-FIN-010 — casamento

    @Bean
    public ConciliacaoJdbc conciliacaoJdbc(DataSource dataSource, ObjectMapper json) {
        return new ConciliacaoJdbc(JdbcClient.create(dataSource), json);
    }

    @Bean
    public Conciliador conciliador(ConciliacaoJdbc conciliacao, EstabelecimentoApi estabelecimentos) {
        return new Conciliador(conciliacao, estabelecimentos);
    }

    @Bean
    public ConciliarUseCase conciliarUseCase(Conciliador conciliador, Relogio relogio) {
        return new ConciliarUseCase(conciliador, relogio);
    }

    @Bean
    public ConsultarConciliacaoUseCase consultarConciliacaoUseCase(
            ConciliacaoJdbc conciliacao, ResolucoesJdbc resolucoes, NomesDeUsuariosApi nomes,
            EstabelecimentoApi estabelecimentos, Relogio relogio) {
        return new ConsultarConciliacaoUseCase(conciliacao, resolucoes, nomes, estabelecimentos,
                relogio);
    }

    // ------------------------------------------------------------------ RT-FIN-011 — resolver

    @Bean
    public ResolucoesJdbc resolucoesJdbc(DataSource dataSource, ObjectMapper json) {
        return new ResolucoesJdbc(JdbcClient.create(dataSource), json);
    }

    @Bean
    public ResolverDivergenciaUseCase resolverDivergenciaUseCase(
            ResolucoesJdbc resolucoes, LancamentosJdbc lancamentos, RazaoApi razao,
            Competencia competencia, Relogio relogio, AuditoriaApi auditoria) {
        return new ResolverDivergenciaUseCase(resolucoes, lancamentos, razao, competencia, relogio,
                auditoria);
    }

    @Bean
    public ListarImportacoesUseCase listarImportacoesUseCase(ImportacoesDeExtratoJdbc importacoes,
                                                             NomesDeUsuariosApi nomes) {
        return new ListarImportacoesUseCase(importacoes, nomes);
    }
}
