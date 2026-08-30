package br.com.salao.iam.internal;

import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.internal.application.AutenticarUseCase;
import br.com.salao.iam.internal.application.EncerrarSessaoUseCase;
import br.com.salao.iam.internal.application.RenovarAcessoUseCase;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.iam.internal.infra.CredenciaisJdbc;
import br.com.salao.iam.internal.infra.EmissorDeTokenJwt;
import br.com.salao.iam.internal.infra.EstabelecimentoCacheado;
import br.com.salao.iam.internal.infra.PurgadorDeRefreshTokens;
import br.com.salao.iam.internal.infra.RefreshTokensJdbc;
import br.com.salao.iam.internal.infra.EstabelecimentoJdbc;
import br.com.salao.shared.manutencao.ConexaoDeManutencao;
import br.com.salao.shared.tempo.Relogio;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

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
            ConexaoDeManutencao plataforma, PasswordEncoder codificadorDeSenha) {
        return new ProvisionarEstabelecimentoUseCase(plataforma, codificadorDeSenha);
    }

    @Bean
    public CredenciaisJdbc credenciaisJdbc(ConexaoDeManutencao plataforma, DataSource dataSource) {
        return new CredenciaisJdbc(plataforma, JdbcClient.create(dataSource));
    }

    @Bean
    public EmissorDeTokenJwt emissorDeTokenJwt(
            JwtEncoder codificador, Relogio relogio,
            @Value("${app.jwt.validade-do-acesso:PT15M}") Duration validade,
            @Value("${app.jwt.emissor:salao}") String emissor) {
        return new EmissorDeTokenJwt(codificador, relogio, validade, emissor);
    }

    @Bean
    public RefreshTokensJdbc refreshTokensJdbc(ConexaoDeManutencao plataforma,
                                               DataSource dataSource) {
        return new RefreshTokensJdbc(plataforma, JdbcClient.create(dataSource));
    }

    @Bean
    public AutenticarUseCase autenticarUseCase(
            CredenciaisJdbc credenciais, PasswordEncoder codificador, EmissorDeTokenJwt emissor,
            RefreshTokensJdbc refreshTokens, Relogio relogio,
            @Value("${app.auth.refresh.validade:P30D}") Duration validadeDoRefresh) {
        return new AutenticarUseCase(credenciais, codificador, emissor, refreshTokens, relogio,
                validadeDoRefresh);
    }

    @Bean
    public EncerrarSessaoUseCase encerrarSessaoUseCase(RefreshTokensJdbc tokens, Relogio relogio) {
        return new EncerrarSessaoUseCase(tokens, relogio);
    }

    @Bean
    public PurgadorDeRefreshTokens purgadorDeRefreshTokens(
            RefreshTokensJdbc tokens,
            @Value("${app.auth.refresh.retencao-alem-do-vencimento:P30D}") Duration retencao) {
        return new PurgadorDeRefreshTokens(tokens, retencao);
    }

    @Bean
    public RenovarAcessoUseCase renovarAcessoUseCase(
            RefreshTokensJdbc tokens, CredenciaisJdbc credenciais, EmissorDeTokenJwt emissor,
            Relogio relogio,
            @Value("${app.auth.refresh.validade:P30D}") Duration validade,
            @Value("${app.auth.refresh.tolerancia-de-reenvio:PT10S}") Duration tolerancia,
            MeterRegistry registro) {
        return new RenovarAcessoUseCase(tokens, credenciais, emissor, relogio, validade,
                tolerancia, registro);
    }
}
