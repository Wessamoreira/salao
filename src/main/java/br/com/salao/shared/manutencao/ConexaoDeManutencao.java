package br.com.salao.shared.manutencao;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * RT-INF-005 — a conexão da role {@code salao_manutencao}, encapsulada.
 *
 * <p><strong>Não é um bean do tipo {@link javax.sql.DataSource}, e isso é essencial.</strong>
 * O {@code DataSourceAutoConfiguration} do Boot é {@code @ConditionalOnMissingBean(DataSource)}:
 * publicar um segundo {@code DataSource} desliga o da aplicação por inteiro. O sintoma aparece
 * longe da causa — o Hibernate falha com <em>"Unable to determine Dialect without JDBC
 * metadata"</em>, e nada menciona o bean extra.
 *
 * <p>Encapsulando, o tipo publicado é este, o {@code DataSource} da aplicação continua
 * autoconfigurado, e o pool de manutenção ainda é fechado no shutdown.
 */
public class ConexaoDeManutencao implements DisposableBean {

    private final HikariDataSource dataSource;
    private final JdbcClient jdbc;

    public ConexaoDeManutencao(String url, String usuario, String senha) {
        this.dataSource = new HikariDataSource();
        this.dataSource.setJdbcUrl(url);
        this.dataSource.setUsername(usuario);
        this.dataSource.setPassword(senha);
        // Manutenção é periódica e serial. Pool maior só tiraria conexão do banco sem servir
        // a ninguém.
        this.dataSource.setMaximumPoolSize(2);
        this.dataSource.setPoolName("manutencao");
        this.jdbc = JdbcClient.create(this.dataSource);
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    @Override
    public void destroy() {
        dataSource.close();
    }
}
