package br.com.salao.atendimento.internal;

import br.com.salao.agenda.api.AgendaApi;
import br.com.salao.atendimento.internal.application.AbrirComandaUseCase;
import br.com.salao.atendimento.internal.application.ListarComandasAbertasUseCase;
import br.com.salao.atendimento.internal.infra.ComandasJdbc;
import br.com.salao.cliente.api.ClienteApi;
import br.com.salao.iam.api.AuditoriaApi;
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
    public ListarComandasAbertasUseCase listarComandasAbertasUseCase(ComandasJdbc comandas,
                                                                     ClienteApi clientes) {
        return new ListarComandasAbertasUseCase(comandas, clientes);
    }
}
