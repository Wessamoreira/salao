package br.com.salao.shared.cache;

import br.com.salao.shared.tenant.TenantContext;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
    private final CacheManager caches;

    public InvalidadorDeCache(JdbcClient jdbc, ObjectMapper json, CacheManager caches) {
        this.jdbc = jdbc;
        this.json = json;
        this.caches = caches;
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
        limparLocalAoCommitar(invalidacao);
    }

    /**
     * Limpa também o cache DESTA instância, assim que a transação commitar.
     *
     * <h2>Por que o NOTIFY sozinho não bastava</h2>
     *
     * <p>O ouvinte recebe o aviso pela própria conexão dedicada, de forma assíncrona — inclusive
     * o aviso que esta instância acabou de mandar. Existe, portanto, uma janela curta em que quem
     * escreveu continua lendo o valor velho.
     *
     * <p>Parece detalhe e não é: em RT-CFG-001 significa o salão salvar o DDD padrão e o cadastro
     * de cliente seguinte ainda recusar o telefone — a pessoa conclui que "não salvou" e tenta de
     * novo. Quem clicou em salvar precisa ver o efeito.
     *
     * <p>Faltava, e só apareceu quando a invalidação ganhou o primeiro uso real. As outras
     * instâncias continuam dependendo do {@code NOTIFY}, e o TTL segue como rede de segurança se
     * ele se perder.
     *
     * <h2>Depois do commit, nunca antes</h2>
     *
     * <p>Limpar antes abriria a pior janela possível: uma leitura concorrente repovoaria o cache
     * com o valor <em>antigo</em>, ainda não sobrescrito, e ele ficaria lá até o TTL — o oposto do
     * que se queria.
     */
    private void limparLocalAoCommitar(Invalidacao invalidacao) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            aplicarLocalmente(invalidacao);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                aplicarLocalmente(invalidacao);
            }
        });
    }

    private void aplicarLocalmente(Invalidacao invalidacao) {
        Cache cache = caches.getCache(invalidacao.cache());
        if (cache == null) {
            return;
        }
        if (invalidacao.chave() == null) {
            cache.clear();
        } else {
            cache.evict(ChaveDeCache.de(invalidacao.estabelecimentoId(), invalidacao.chave()));
        }
    }
}
