package br.com.salao.shared.manutencao;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * RT-INF-005 — a conexão de manutenção.
 *
 * <p>Purga de idempotência, expurgo de outbox e retenção de auditoria são operações
 * legitimamente cross-tenant. Elas <strong>não</strong> podem usar a conexão da aplicação: dar
 * poder cross-tenant a {@code salao_app} desfaz exatamente o que a RLS garante.
 *
 * <p>Por isso existe uma terceira role, {@code salao_manutencao}, com policy própria nas tabelas
 * elegíveis (ver {@code V4__idempotencia.sql}). A permissão é de quem se conecta — não de quem
 * lembra de definir uma variável de sessão, que qualquer conexão poderia definir.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class ManutencaoConfig {

    @Bean
    public ConexaoDeManutencao conexaoDeManutencao(
            @Value("${app.manutencao.url}") String url,
            @Value("${app.manutencao.username}") String usuario,
            @Value("${app.manutencao.password}") String senha) {
        return new ConexaoDeManutencao(url, usuario, senha);
    }
}
