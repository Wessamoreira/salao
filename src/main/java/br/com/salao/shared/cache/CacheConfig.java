package br.com.salao.shared.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** RT-INF-007 — cache local com invalidação por LISTEN/NOTIFY. */
@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CacheConfig {

    /**
     * {@code expireAfterWrite} de 30 minutos <strong>não é ajuste de performance: é o teto do
     * estrago</strong> quando o ouvinte cair e invalidações se perderem. No pior caso, meia hora
     * de preço velho. Aumentar isso confiando no {@code NOTIFY} é trocar a garantia pela
     * otimização.
     *
     * <p>O plano original previa {@code refreshAfterWrite}. Ele foi retirado: exige um
     * {@code CacheLoader}, que o modelo de {@code @Cacheable} não tem — o Caffeine falha na
     * construção. Só faria sentido com cache de leitura programática, e não é o caso aqui.
     */
    @Bean
    public CacheManager cacheManager() {
        var gerenciador = new CaffeineCacheManager();
        gerenciador.setCaffeine(Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(java.time.Duration.ofMinutes(30))
                .recordStats());
        return gerenciador;
    }

    /**
     * Padrão de propósito: um {@code @Cacheable} escrito sem pensar em multi-tenant já nasce
     * isolado. Ver {@link GeradorDeChaveComTenant}.
     */
    @Bean("keyGenerator")
    public KeyGenerator geradorDeChaveComTenant() {
        return new GeradorDeChaveComTenant();
    }

    /** Ver {@link ChavesDeCache} — nome curto porque aparece no SpEL das anotações. */
    @Bean("chaveDeCache")
    public ChavesDeCache chavesDeCache() {
        return new ChavesDeCache();
    }

    @Bean
    public InvalidadorDeCache invalidadorDeCache(DataSource dataSource, ObjectMapper json) {
        return new InvalidadorDeCache(JdbcClient.create(dataSource), json);
    }

    /**
     * Conexão própria, montada a partir das credenciais da aplicação em vez de tirada do
     * {@code DataSource}: uma conexão presa em {@code LISTEN} não pode sair do pool do Hikari.
     */
    @Bean
    public OuvinteDeInvalidacao ouvinteDeInvalidacao(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String usuario,
            @Value("${spring.datasource.password}") String senha,
            CacheManager cacheManager, ObjectMapper json) {
        return new OuvinteDeInvalidacao(url, usuario, senha, cacheManager, json);
    }

    /**
     * Sem esta métrica, o ouvinte cair é invisível: nada falha, nada alerta, e o cache passa a
     * servir dado velho até o TTL. É o segundo modo de falha silenciosa do projeto — o primeiro
     * é o outbox travado.
     */
    @Bean
    public MeterBinder metricaDoOuvinteDeCache(OuvinteDeInvalidacao ouvinte) {
        return (MeterRegistry registro) -> Gauge
                .builder("cache.listener.up", ouvinte, o -> o.conectado() ? 1 : 0)
                .description("1 quando o ouvinte de invalidação está conectado; 0 quando caiu e "
                        + "o cache local depende apenas do TTL")
                .register(registro);
    }
}
