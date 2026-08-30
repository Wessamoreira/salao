package br.com.salao.shared.tenant;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.transaction.PlatformTransactionManager;

/** RT-INF-002 — fiação do isolamento por tenant. */
@Configuration(proxyBeanMethods = false)
public class TenantConfig {

    /**
     * Substitui o {@code JpaTransactionManager} padrão do Boot. Sem isto, nenhuma transação
     * recebe {@code app.tenant_id} e a RLS filtra tudo — o sistema inteiro lê zero linhas.
     */
    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf,
                                                        ObjectProvider<MeterRegistry> registro) {
        var tm = new TenantAwareTransactionManager();
        tm.setEntityManagerFactory(emf);
        // ObjectProvider: o gerenciador de transação precisa existir mesmo sem Micrometer,
        // e depender dele criaria um ciclo com as métricas que usam o banco.
        registro.ifAvailable(tm::setMeterRegistry);
        return tm;
    }

    /**
     * Só em dev e test, e agora como <em>último</em> recurso: o resolvedor do JWT tem
     * {@code @Order(0)} e ganha sempre que houver login de verdade. Ver o aviso em
     * {@link ResolvedorDeTenantPorCabecalho}.
     */
    @Bean
    @Order(100)
    @Profile({"dev", "test"})
    public ResolvedorDeTenant resolvedorDeTenantPorCabecalho() {
        return new ResolvedorDeTenantPorCabecalho();
    }



    /**
     * Depois do Spring Security (cuja cadeia fica em {@code -100}), porque o resolvedor do JWT lê o
     * {@code SecurityContext} — que só existe depois da autenticação. E antes de qualquer coisa
     * que abra transação, porque a transação exige o escopo já aberto.
     */
    @Bean
    public FilterRegistrationBean<TenantFilter> tenantFilter(
            List<ResolvedorDeTenant> resolvedores) {
        var registro = new FilterRegistrationBean<>(new TenantFilter(resolvedores));
        registro.setOrder(Ordered.HIGHEST_PRECEDENCE + 90);
        return registro;
    }
}
