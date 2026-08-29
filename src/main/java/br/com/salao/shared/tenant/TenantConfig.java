package br.com.salao.shared.tenant;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
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
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        var tm = new TenantAwareTransactionManager();
        tm.setEntityManagerFactory(emf);
        return tm;
    }

    /** Só em dev e test: ver o aviso em {@link ResolvedorDeTenantPorCabecalho}. */
    @Bean
    @Profile({"dev", "test"})
    public ResolvedorDeTenant resolvedorDeTenantPorCabecalho() {
        return new ResolvedorDeTenantPorCabecalho();
    }

    /**
     * Fora de dev e test não há, ainda, forma legítima de resolver o tenant — o JWT só chega em
     * RT-IAM-002. Este resolvedor devolve sempre {@code null}, de modo que toda transação falhe
     * com {@link TenantNaoDefinidoException}. Falha barulhenta é o comportamento certo aqui:
     * a alternativa seria aceitar o cabeçalho em produção, que é troca de identidade por HTTP.
     */
    @Bean
    @Profile("!dev & !test")
    public ResolvedorDeTenant resolvedorDeTenantIndisponivel() {
        return requisicao -> null;
    }

    @Bean
    public FilterRegistrationBean<TenantFilter> tenantFilter(ResolvedorDeTenant resolvedor) {
        var registro = new FilterRegistrationBean<>(new TenantFilter(resolvedor));
        // Antes de tudo: qualquer coisa que abra transação já precisa do escopo aberto.
        registro.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registro;
    }
}
