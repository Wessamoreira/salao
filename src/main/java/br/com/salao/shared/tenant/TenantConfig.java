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
     * A cadeia do Spring Security fica em {@code SecurityProperties.DEFAULT_FILTER_ORDER}, que
     * vale {@code -100}. Este filtro precisa vir <strong>logo depois</strong> dela: o resolvedor
     * do JWT lê o {@code SecurityContext}, que só existe após a autenticação. E antes de qualquer
     * coisa que abra transação, porque a transação exige o escopo já aberto.
     *
     * <p><strong>Valor absoluto, não {@code HIGHEST_PRECEDENCE + n}.</strong> A primeira versão
     * usava {@code Ordered.HIGHEST_PRECEDENCE + 90}, que é {@code Integer.MIN_VALUE + 90} — um
     * número ordens de grandeza <em>antes</em> de -100, exatamente o contrário da intenção. O erro
     * ficou invisível enquanto não havia endpoint autenticado, e apareceu como
     * {@code TenantNaoDefinidoException} no primeiro deles.
     */
    private static final int DEPOIS_DO_SPRING_SECURITY = -90;
    @Bean
    public FilterRegistrationBean<TenantFilter> tenantFilter(
            List<ResolvedorDeTenant> resolvedores) {
        var registro = new FilterRegistrationBean<>(new TenantFilter(resolvedores));
        registro.setOrder(DEPOIS_DO_SPRING_SECURITY);
        return registro;
    }
}
