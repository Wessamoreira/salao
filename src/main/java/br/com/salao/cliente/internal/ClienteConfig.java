package br.com.salao.cliente.internal;

import br.com.salao.cliente.api.ClienteApi;
import br.com.salao.cliente.internal.application.AnonimizarClienteUseCase;
import br.com.salao.cliente.internal.application.AtualizarClienteUseCase;
import br.com.salao.cliente.internal.application.BuscarClientesUseCase;
import br.com.salao.cliente.internal.application.CadastrarClienteUseCase;
import br.com.salao.cliente.internal.application.ConsultarClienteUseCase;
import br.com.salao.cliente.internal.application.ConsultarConsentimentosUseCase;
import br.com.salao.cliente.internal.application.RegistrarConsentimentoUseCase;
import br.com.salao.cliente.internal.application.DetectarDuplicadosUseCase;
import br.com.salao.cliente.internal.application.MesclarClientesUseCase;
import br.com.salao.cliente.internal.infra.ClientesJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.shared.tempo.Relogio;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/** RT-CLI-001 — fiação do módulo cliente. */
@Configuration(proxyBeanMethods = false)
public class ClienteConfig {

    @Bean
    public ClientesJdbc clientesJdbc(DataSource dataSource) {
        return new ClientesJdbc(JdbcClient.create(dataSource));
    }

    /**
     * O que os outros módulos recebem ao injetar {@link ClienteApi}.
     *
     * <p>{@code @Primary} é necessário porque {@code ClientesJdbc} também é um bean e também
     * implementa {@code ClienteApi} — sem ele há dois candidatos do mesmo tipo. Faltava, e a
     * injeção só funcionava por acidente: o Spring desempata pelo NOME do campo quando há mais de
     * um candidato, e todos os testes existentes chamavam o campo de {@code clienteApi}. O
     * primeiro que usou outro nome quebrou. {@code IamConfig} já resolvia assim para
     * {@code EstabelecimentoApi}.
     */
    @Bean
    @Primary
    public ClienteApi clienteApi(ClientesJdbc jdbc) {
        return jdbc;
    }

    @Bean
    public CadastrarClienteUseCase cadastrarClienteUseCase(
            ClientesJdbc clientes, EstabelecimentoApi estabelecimentos, AuditoriaApi auditoria) {
        return new CadastrarClienteUseCase(clientes, estabelecimentos, auditoria);
    }

    @Bean
    public ConsultarClienteUseCase consultarClienteUseCase(ClientesJdbc clientes) {
        return new ConsultarClienteUseCase(clientes);
    }

    @Bean
    public BuscarClientesUseCase buscarClientesUseCase(ClientesJdbc clientes) {
        return new BuscarClientesUseCase(clientes);
    }

    @Bean
    public AnonimizarClienteUseCase anonimizarClienteUseCase(
            ClientesJdbc clientes, AuditoriaApi auditoria, Relogio relogio) {
        return new AnonimizarClienteUseCase(clientes, auditoria, relogio);
    }

    @Bean
    public AtualizarClienteUseCase atualizarClienteUseCase(
            ClientesJdbc clientes, EstabelecimentoApi estabelecimentos, AuditoriaApi auditoria) {
        return new AtualizarClienteUseCase(clientes, estabelecimentos, auditoria);
    }

    @Bean
    public RegistrarConsentimentoUseCase registrarConsentimentoUseCase(
            ClientesJdbc clientes, AuditoriaApi auditoria, Relogio relogio) {
        return new RegistrarConsentimentoUseCase(clientes, auditoria, relogio);
    }

    @Bean
    public ConsultarConsentimentosUseCase consultarConsentimentosUseCase(ClientesJdbc clientes) {
        return new ConsultarConsentimentosUseCase(clientes);
    }

    @Bean
    public DetectarDuplicadosUseCase detectarDuplicadosUseCase(ClientesJdbc clientes) {
        return new DetectarDuplicadosUseCase(clientes);
    }

    @Bean
    public MesclarClientesUseCase mesclarClientesUseCase(ClientesJdbc clientes,
                                                         AuditoriaApi auditoria, Relogio relogio) {
        return new MesclarClientesUseCase(clientes, auditoria, relogio);
    }
}
