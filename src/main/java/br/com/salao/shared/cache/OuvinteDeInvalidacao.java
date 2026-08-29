package br.com.salao.shared.cache;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.SmartLifecycle;
import tools.jackson.databind.ObjectMapper;

/**
 * RT-INF-007 — ouve o canal de invalidação e limpa o cache local.
 *
 * <h2>Conexão dedicada, fora do pool</h2>
 *
 * <p>Uma conexão presa em {@code LISTEN} é uma conexão a menos no Hikari <em>para sempre</em>.
 * Com pool pequeno numa VM pequena, isso é um caminho direto para exaustão sob carga — e o
 * sintoma apareceria como lentidão em requisições que nada têm a ver com cache.
 *
 * <h2>{@code LISTEN/NOTIFY} não é durável, e é isto que se faz a respeito</h2>
 *
 * <p>Se esta conexão cair, as invalidações emitidas no intervalo <strong>somem</strong> — não há
 * fila nem reentrega. A instância seguiria servindo preço velho sem nenhum sinal.
 *
 * <p>Por isso, toda reconexão <strong>limpa o cache inteiro</strong>. É deliberadamente grosseiro:
 * o custo é recarregar do Postgres, e a alternativa é servir dado errado sem saber. Somado ao
 * {@code expireAfterWrite} de 30 minutos, que é o teto do estrago, e à métrica
 * {@code cache.listener.up}, que torna a queda visível.
 *
 * <p><strong>Não aumente o TTL confiando no {@code NOTIFY}.</strong> Ele é a otimização; o TTL é a
 * garantia.
 */
public class OuvinteDeInvalidacao implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OuvinteDeInvalidacao.class);

    private static final Duration ESPERA_MINIMA = Duration.ofSeconds(1);
    private static final Duration ESPERA_MAXIMA = Duration.ofSeconds(30);
    private static final int TIMEOUT_LEITURA_MS = 1_000;

    public static final String NOME_DA_CONEXAO = "salao-ouvinte-cache";

    private final String url;
    private final String usuario;
    private final String senha;
    private final CacheManager cacheManager;
    private final ObjectMapper json;

    private final AtomicBoolean conectado = new AtomicBoolean(false);
    private volatile boolean rodando;
    private volatile Thread thread;

    public OuvinteDeInvalidacao(String url, String usuario, String senha,
                                CacheManager cacheManager, ObjectMapper json) {
        this.url = url;
        this.usuario = usuario;
        this.senha = senha;
        this.cacheManager = cacheManager;
        this.json = json;
    }

    public boolean conectado() {
        return conectado.get();
    }

    @Override
    public void start() {
        rodando = true;
        thread = Thread.ofVirtual().name("ouvinte-cache").start(this::laco);
    }

    @Override
    public void stop() {
        rodando = false;
        Thread atual = thread;
        if (atual != null) {
            atual.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return rodando;
    }

    private void laco() {
        Duration espera = ESPERA_MINIMA;
        while (rodando) {
            try (Connection conexao = conectar()) {
                espera = ESPERA_MINIMA;
                ouvir(conexao);
            } catch (Exception e) {
                conectado.set(false);
                if (!rodando) {
                    return;
                }
                log.warn("Ouvinte de invalidação caiu; reconectando em {}s. "
                        + "Enquanto isso o cache local depende só do TTL.", espera.toSeconds(), e);
                dormir(espera);
                espera = proximaEspera(espera);
            }
        }
    }

    private Connection conectar() throws Exception {
        var propriedades = new java.util.Properties();
        propriedades.setProperty("user", usuario);
        propriedades.setProperty("password", senha);
        // Aparece em pg_stat_activity: é assim que se identifica esta conexão quando alguém
        // investiga conexões ociosas no banco — e é como o teste de reconexão a encontra.
        propriedades.setProperty("ApplicationName", NOME_DA_CONEXAO);
        Connection conexao = DriverManager.getConnection(url, propriedades);
        try (var st = conexao.createStatement()) {
            st.execute("LISTEN " + InvalidadorDeCache.CANAL);
        }
        // Enquanto estivemos fora, invalidações podem ter passado. Não há como saber quais —
        // então nada do que está em memória é confiável.
        limparTudo();
        conectado.set(true);
        log.info("Ouvinte de invalidação conectado; cache local esvaziado por precaução");
        return conexao;
    }

    private void ouvir(Connection conexao) throws Exception {
        var pg = conexao.unwrap(PGConnection.class);
        while (rodando) {
            var notificacoes = pg.getNotifications(TIMEOUT_LEITURA_MS);
            if (notificacoes == null) {
                continue;
            }
            for (var notificacao : notificacoes) {
                aplicar(notificacao.getParameter());
            }
        }
    }

    void aplicar(String payload) {
        Invalidacao invalidacao;
        try {
            invalidacao = json.readValue(payload, Invalidacao.class);
        } catch (RuntimeException e) {
            // Payload ilegível não pode derrubar o ouvinte: perder uma invalidação é ruim,
            // perder todas as seguintes é muito pior.
            log.error("Payload de invalidação ilegível, ignorado: {}", payload, e);
            return;
        }

        var cache = cacheManager.getCache(invalidacao.cache());
        if (cache == null) {
            return;   // outra instância pode ter caches que esta ainda não criou
        }
        if (invalidacao.chave() != null) {
            cache.evict(ChaveDeCache.de(invalidacao.estabelecimentoId(), invalidacao.chave()));
            return;
        }
        limparDoEstabelecimento(cache, invalidacao);
    }

    /**
     * Limpa só as entradas de um estabelecimento. O {@code Cache} do Spring não oferece remoção
     * por prefixo, então descemos ao mapa do Caffeine — a alternativa seria {@code clear()},
     * punindo todos os outros estabelecimentos por causa de um.
     */
    private void limparDoEstabelecimento(org.springframework.cache.Cache cache,
                                         Invalidacao invalidacao) {
        if (cache instanceof CaffeineCache caffeine) {
            caffeine.getNativeCache().asMap().keySet()
                    .removeIf(chave -> ChaveDeCache.pertenceA(chave,
                            invalidacao.estabelecimentoId()));
        } else {
            cache.clear();
        }
    }

    private void limparTudo() {
        cacheManager.getCacheNames().forEach(nome -> {
            var cache = cacheManager.getCache(nome);
            if (cache != null) {
                cache.clear();
            }
        });
    }

    private static Duration proximaEspera(Duration atual) {
        Duration dobro = atual.multipliedBy(2);
        return dobro.compareTo(ESPERA_MAXIMA) > 0 ? ESPERA_MAXIMA : dobro;
    }

    private void dormir(Duration duracao) {
        try {
            Thread.sleep(duracao);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            rodando = false;
        }
    }
}
