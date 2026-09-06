package br.com.salao.atendimento.internal;

import br.com.salao.agenda.api.AgendaApi;
import br.com.salao.atendimento.internal.application.AbrirComandaUseCase;
import br.com.salao.atendimento.internal.application.AdicionarItemDeProdutoUseCase;
import br.com.salao.atendimento.internal.application.AdicionarItemDeServicoUseCase;
import br.com.salao.atendimento.internal.application.AdicionarExecutanteUseCase;
import br.com.salao.atendimento.internal.application.AplicarDescontoUseCase;
import br.com.salao.atendimento.internal.application.RemoverExecutanteUseCase;
import br.com.salao.atendimento.internal.application.ConsultarComandaUseCase;
import br.com.salao.atendimento.internal.application.RemoverItemUseCase;
import br.com.salao.atendimento.internal.application.ListarComandasAbertasUseCase;
import br.com.salao.atendimento.internal.infra.ComandasJdbc;
import br.com.salao.catalogo.api.ServicoApi;
import br.com.salao.cliente.api.ClienteApi;
import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.estoque.api.ProdutoApi;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.LimitesDoUsuarioApi;
import br.com.salao.shared.tempo.Relogio;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** RT-ATD-001 — fiação do módulo atendimento. */
@Configuration(proxyBeanMethods = false)
public class AtendimentoConfig {

    @Bean
    public ComandasJdbc comandasJdbc(DataSource dataSource) {
        return new ComandasJdbc(JdbcClient.create(dataSource));
    }

    @Bean
    public AbrirComandaUseCase abrirComandaUseCase(ComandasJdbc comandas, ClienteApi clientes,
                                                   AgendaApi agenda, AuditoriaApi auditoria,
                                                   Relogio relogio) {
        return new AbrirComandaUseCase(comandas, clientes, agenda, auditoria, relogio);
    }

    @Bean
    public AdicionarItemDeServicoUseCase adicionarItemDeServicoUseCase(
            ComandasJdbc comandas, ServicoApi servicos, ProfissionalApi profissionais,
            AgendaApi agenda, EstabelecimentoApi estabelecimentos, AuditoriaApi auditoria,
            Relogio relogio) {
        return new AdicionarItemDeServicoUseCase(comandas, servicos, profissionais, agenda,
                estabelecimentos, auditoria, relogio);
    }

    @Bean
    public AdicionarItemDeProdutoUseCase adicionarItemDeProdutoUseCase(
            ComandasJdbc comandas, ProdutoApi produtos, AuditoriaApi auditoria, Relogio relogio) {
        return new AdicionarItemDeProdutoUseCase(comandas, produtos, auditoria, relogio);
    }

    @Bean
    public AplicarDescontoUseCase aplicarDescontoUseCase(
            ComandasJdbc comandas, LimitesDoUsuarioApi limites,
            EstabelecimentoApi estabelecimentos, AuditoriaApi auditoria) {
        return new AplicarDescontoUseCase(comandas, limites, estabelecimentos, auditoria);
    }

    @Bean
    public AdicionarExecutanteUseCase adicionarExecutanteUseCase(
            ComandasJdbc comandas, ProfissionalApi profissionais,
            EstabelecimentoApi estabelecimentos, AuditoriaApi auditoria) {
        return new AdicionarExecutanteUseCase(comandas, profissionais, estabelecimentos, auditoria);
    }

    @Bean
    public RemoverExecutanteUseCase removerExecutanteUseCase(ComandasJdbc comandas,
                                                             AuditoriaApi auditoria) {
        return new RemoverExecutanteUseCase(comandas, auditoria);
    }

    @Bean
    public RemoverItemUseCase removerItemUseCase(ComandasJdbc comandas, AuditoriaApi auditoria) {
        return new RemoverItemUseCase(comandas, auditoria);
    }

    @Bean
    public ConsultarComandaUseCase consultarComandaUseCase(ComandasJdbc comandas) {
        return new ConsultarComandaUseCase(comandas);
    }

    @Bean
    public ListarComandasAbertasUseCase listarComandasAbertasUseCase(ComandasJdbc comandas,
                                                                     ClienteApi clientes) {
        return new ListarComandasAbertasUseCase(comandas, clientes);
    }
}
