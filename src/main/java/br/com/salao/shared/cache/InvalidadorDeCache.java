package br.com.salao.shared.cache;

import br.com.salao.shared.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * RT-INF-007 — avisa todas as instâncias de que uma entrada morreu.
 *
 * <h2>O {@code NOTIFY} vai DENTRO da transação</h2>
 *
 * <p>O Postgres enfileira o {@code NOTIFY} e só o entrega no commit — e o descarta no rollback.
 * Essa é exatamente a semântica desejada, e ela vem de graça.
 *
 * <p>A alternativa aparentemente mais correta — emitir de um
 * {@code @TransactionalEventListener(AFTER_COMMIT)} — é pior: entre o commit e o envio existe uma
 * janela em que o processo pode morrer, e aí o preço novo está no banco enquanto as outras
 * instâncias seguem servindo o antigo. Sem erro, sem alerta, até o TTL vencer.
 *
 * <p>Depende de o {@code JdbcClient} usar a conexão da transação em curso, e não uma nova do pool.
 * Ele usa: {@code JpaTransactionManager} deriva o {@code DataSource} da
 * {@code EntityManagerFactory} e liga a conexão ao contexto transacional. O teste
 * {@code invalidacao_em_transacao_revertida_nao_propaga} é o que prova — se a conexão fosse outra,
 * o {@code NOTIFY} escaparia mesmo com rollback.
 */
public class InvalidadorDeCache {

    public static final String CANAL = "cache_invalidacao";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public InvalidadorDeCache(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Invalida uma entrada em todas as instâncias, ao commitar. */
    public void invalidar(String cache, Object chaveDeNegocio) {
        publicar(Invalidacao.deChave(TenantContext.obrigatorio(), cache,
                String.valueOf(chaveDeNegocio)));
    }

    /** Invalida um cache inteiro, mas só deste estabelecimento. */
    public void invalidarCacheInteiro(String cache) {
        publicar(Invalidacao.deCacheInteiro(TenantContext.obrigatorio(), cache));
    }

    private void publicar(Invalidacao invalidacao) {
        jdbc.sql("select pg_notify(?, ?)")
                .param(1, CANAL)
                .param(2, json.writeValueAsString(invalidacao))
                .query(String.class)
                .optional();
    }
}
