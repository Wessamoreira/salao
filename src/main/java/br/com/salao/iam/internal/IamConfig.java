package br.com.salao.iam.internal;

import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.internal.application.AutenticarUseCase;
import br.com.salao.iam.internal.application.AbridorDeSessao;
import br.com.salao.iam.internal.application.ConsultarCapacidadesUseCase;
import br.com.salao.iam.internal.application.EncerrarSessaoUseCase;
import br.com.salao.iam.internal.application.RenovarAcessoUseCase;
import br.com.salao.iam.internal.application.SegundoFatorUseCase;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.iam.internal.infra.CredenciaisJdbc;
import br.com.salao.iam.internal.infra.EmissorDeTokenJwt;
import br.com.salao.iam.internal.infra.EstabelecimentoCacheado;
import br.com.salao.iam.internal.infra.MfaJdbc;
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
import br.com.salao.shared.cripto.CofreDeCampo;
import org.springframework.security.oauth2.jwt.JwtDecoder;
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
    public AbridorDeSessao abridorDeSessao(
            CredenciaisJdbc credenciais, EmissorDeTokenJwt emissor,
            RefreshTokensJdbc refreshTokens, Relogio relogio,
            @Value("${app.auth.refresh.validade:P30D}") Duration validadeDoRefresh) {
        return new AbridorDeSessao(credenciais, emissor, refreshTokens, relogio,
                validadeDoRefresh);
    }

    @Bean
    public AutenticarUseCase autenticarUseCase(
            CredenciaisJdbc credenciais, PasswordEncoder codificador, EmissorDeTokenJwt emissor,
            AbridorDeSessao abridor, Relogio relogio) {
        return new AutenticarUseCase(credenciais, codificador, emissor, abridor, relogio);
    }

    /**
     * Chave fora do banco — variável de ambiente hoje, gerenciador de segredos em produção.
     * Guardá-la junto do dado cifrado tornaria a cifragem decorativa.
     */
    @Bean
    public CofreDeCampo cofreDeCampo(@Value("${app.cripto.chave}") String chaveBase64) {
        return new CofreDeCampo(chaveBase64);
    }

    @Bean
    public MfaJdbc mfaJdbc(DataSource dataSource, CofreDeCampo cofre) {
        return new MfaJdbc(JdbcClient.create(dataSource), cofre);
    }

    @Bean
    public SegundoFatorUseCase segundoFatorUseCase(MfaJdbc mfa, CredenciaisJdbc credenciais,
                                                   EstabelecimentoApi estabelecimentos,
                                                   AbridorDeSessao abridor, JwtDecoder decoder,
                                                   Relogio relogio) {
        return new SegundoFatorUseCase(mfa, credenciais, estabelecimentos, abridor, decoder,
                relogio);
    }

    @Bean
    public ConsultarCapacidadesUseCase consultarCapacidadesUseCase(
            CredenciaisJdbc credenciais, EstabelecimentoApi estabelecimentos) {
        return new ConsultarCapacidadesUseCase(credenciais, estabelecimentos);
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
