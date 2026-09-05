package br.com.salao.notificacao.internal;

import br.com.salao.cliente.api.ClienteApi;
import br.com.salao.notificacao.api.Mensageiro;
import br.com.salao.notificacao.api.NotificacaoApi;
import br.com.salao.notificacao.internal.application.CatalogoDeModelos;
import br.com.salao.notificacao.internal.application.EnfileirarMensagemUseCase;
import br.com.salao.notificacao.internal.infra.DespachanteDeMensagens;
import br.com.salao.notificacao.internal.infra.MensageiroDeRegistro;
import br.com.salao.notificacao.internal.infra.MensagensJdbc;
import br.com.salao.shared.manutencao.ConexaoDeManutencao;
import br.com.salao.shared.tempo.Relogio;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** RT-NOT-001 — fiação do módulo notificacao. */
@Configuration(proxyBeanMethods = false)
public class NotificacaoConfig {

    @Bean
    public CatalogoDeModelos catalogoDeModelos() {
        return new CatalogoDeModelos();
    }

    @Bean
    public MensagensJdbc mensagensJdbc(DataSource dataSource, ObjectMapper json) {
        return new MensagensJdbc(JdbcClient.create(dataSource), json);
    }

    /**
     * O mensageiro padrão. Um provedor real entra como bean {@code @Primary} e passa na frente
     * deste sem alterar linha nenhuma do módulo — é o sentido do port.
     *
     * <p>A primeira versão usava {@code @ConditionalOnMissingBean}, e estava errada: fora de uma
     * classe de auto-configuração ele depende da ordem de registro dos beans, que o Spring não
     * garante. O sintoma foi um teste recebendo o mensageiro de produção em vez do seu duble — e
     * em produção seria o inverso, com o provedor real ignorado em silêncio.
     */
    @Bean
    public Mensageiro mensageiro() {
        return new MensageiroDeRegistro();
    }

    @Bean
    @Primary
    public NotificacaoApi notificacaoApi(EnfileirarMensagemUseCase useCase) {
        return useCase;
    }

    @Bean
    public EnfileirarMensagemUseCase enfileirarMensagemUseCase(MensagensJdbc mensagens,
                                                               ClienteApi clientes,
                                                               CatalogoDeModelos modelos,
                                                               Relogio relogio) {
        return new EnfileirarMensagemUseCase(mensagens, clientes, modelos, relogio);
    }

    @Bean
    public DespachanteDeMensagens despachanteDeMensagens(ConexaoDeManutencao manutencao,
                                                         Mensageiro mensageiro,
                                                         CatalogoDeModelos modelos,
                                                         ObjectMapper json, Relogio relogio) {
        return new DespachanteDeMensagens(manutencao, mensageiro, modelos, json, relogio);
    }
}
