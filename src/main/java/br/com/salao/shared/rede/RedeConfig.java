package br.com.salao.shared.rede;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** RT-INF-011 — limite de taxa por IP. */
@Configuration(proxyBeanMethods = false)
public class RedeConfig {

    /**
     * Ligar isto é uma afirmação sobre a topologia: <em>nada alcança a aplicação sem passar pelo
     * proxy</em>. Se a porta da aplicação estiver exposta ao lado, o cabeçalho vira uma forma de
     * escolher o próprio IP — e o limite deixa de existir sem parar de parecer que funciona.
     */
    @Bean
    public EnderecoDoCliente enderecoDoCliente(
            @Value("${app.rede.atras-de-proxy:false}") boolean atrasDeProxy) {
        return new EnderecoDoCliente(atrasDeProxy);
    }

    /**
     * Antes do Spring Security ({@code -100}): o limite precisa valer para o login, que é
     * justamente a rota que ninguém precisa estar autenticado para bater.
     */
    @Bean
    public FilterRegistrationBean<LimitadorDeTaxa> limitadorDeTaxa(
            EnderecoDoCliente endereco, MeterRegistry registro,
            @Value("${app.rede.limite-autenticacao:12}") int limiteDeAutenticacao,
            @Value("${app.rede.limite-geral:300}") int limiteGeral) {
        var registroDoFiltro = new FilterRegistrationBean<>(
                new LimitadorDeTaxa(endereco, limiteDeAutenticacao, limiteGeral, registro));
        registroDoFiltro.setOrder(-110);
        return registroDoFiltro;
    }
}
