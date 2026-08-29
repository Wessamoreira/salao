package br.com.salao.shared.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** RT-INF-007 — cache local com invalidação por LISTEN/NOTIFY. */
class CacheIT extends AbstractPostgresIT {

    static final String CACHE = "servicos";

    /** Estado lido por método: o bean é proxiado, e campo de proxy não é campo do alvo. */
    static class CatalogoFalso {
        private final AtomicInteger consultas = new AtomicInteger();

        @Cacheable(value = CACHE, key = "@chaveDeCache.de(#servicoId)")
        public String buscar(String servicoId) {
            consultas.incrementAndGet();
            return "servico-" + servicoId;
        }

        int consultas() {
            return consultas.get();
        }

        void reiniciar() {
            consultas.set(0);
        }
    }

    @TestConfiguration
    static class Configuracao {
        @Bean
        CatalogoFalso catalogoFalso() {
            return new CatalogoFalso();
        }
    }

    @Autowired
    private CatalogoFalso catalogo;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private InvalidadorDeCache invalidador;

    @Autowired
    private OuvinteDeInvalidacao ouvinte;

    @Autowired
    private PlatformTransactionManager gerenciadorDeTransacao;

    private TransactionTemplate tx;

    @BeforeEach
    void prepararCache() {
        tx = new TransactionTemplate(gerenciadorDeTransacao);
        catalogo.reiniciar();
        cacheManager.getCacheNames().forEach(n -> cacheManager.getCache(n).clear());
        aguardar(() -> ouvinte.conectado(), "o ouvinte precisa estar conectado", 20);
    }

    @Test
    @DisplayName("a chave inclui o tenant: o mesmo id em outro estabelecimento não reaproveita")
    void chave_inclui_o_tenant() throws SQLException {
        // Sem isto o cache desfaz a RLS: a resposta viria da memória e o banco nem seria
        // consultado, então nem a política nem a checagem de posse teriam chance de agir.
        UUID a = criarEstabelecimento("Salão A");
        UUID b = criarEstabelecimento("Salão B");

        TenantContext.executar(a, () -> catalogo.buscar("s1"));
        TenantContext.executar(a, () -> catalogo.buscar("s1"));
        assertThat(catalogo.consultas()).as("a segunda veio do cache").isEqualTo(1);

        TenantContext.executar(b, () -> catalogo.buscar("s1"));
        assertThat(catalogo.consultas()).as("outro estabelecimento, outra entrada").isEqualTo(2);
    }

    @Test
    @DisplayName("NOTIFY de outra conexão invalida a entrada local")
    void notificacao_externa_invalida() throws Exception {
        UUID a = criarEstabelecimento("Salão A");
        TenantContext.executar(a, () -> catalogo.buscar("s1"));
        assertThat(catalogo.consultas()).isEqualTo(1);

        notificarDeOutraConexao(a, "s1");

        aguardar(() -> {
            TenantContext.executar(a, () -> catalogo.buscar("s1"));
            return catalogo.consultas() == 2;
        }, "a entrada deveria ter sido invalidada", 20);
    }

    @Test
    @DisplayName("invalidação em transação revertida NÃO propaga; em transação que commita, sim")
    void invalidacao_segue_a_transacao() throws Exception {
        // O teste que justifica emitir o NOTIFY DENTRO da transação. O Postgres o descarta no
        // rollback e o entrega no commit — de graça. Emitir depois do commit, pela aplicação,
        // abriria a janela "commit passou, processo morreu, ninguém invalidou".
        UUID a = criarEstabelecimento("Salão A");
        TenantContext.executar(a, () -> catalogo.buscar("s1"));

        assertThatThrownBy(() -> TenantContext.executar(a, () -> tx.executeWithoutResult(s -> {
            invalidador.invalidar(CACHE, "s1");
            throw new IllegalStateException("negócio falhou depois de invalidar");
        }))).isInstanceOf(IllegalStateException.class);

        Thread.sleep(1_500);
        TenantContext.executar(a, () -> catalogo.buscar("s1"));
        assertThat(catalogo.consultas())
                .as("o rollback descartou o NOTIFY: a entrada continua válida")
                .isEqualTo(1);

        TenantContext.executar(a, () ->
                tx.executeWithoutResult(s -> invalidador.invalidar(CACHE, "s1")));

        aguardar(() -> {
            TenantContext.executar(a, () -> catalogo.buscar("s1"));
            return catalogo.consultas() >= 2;
        }, "o commit deveria ter propagado a invalidação", 20);
    }

    @Test
    @DisplayName("queda do ouvinte: ao reconectar, o cache inteiro é esvaziado")
    void reconexao_esvazia_o_cache() throws Exception {
        // LISTEN/NOTIFY não é durável. O que se perdeu enquanto a conexão esteve fora é
        // desconhecido — então nada do que está em memória é confiável.
        UUID a = criarEstabelecimento("Salão A");
        TenantContext.executar(a, () -> catalogo.buscar("s1"));
        assertThat(catalogo.consultas()).isEqualTo(1);

        derrubarConexaoDoOuvinte();

        aguardar(() -> !ouvinte.conectado(), "o ouvinte deveria ter perdido a conexão", 20);
        aguardar(() -> ouvinte.conectado(), "o ouvinte deveria reconectar sozinho", 60);

        TenantContext.executar(a, () -> catalogo.buscar("s1"));
        assertThat(catalogo.consultas())
                .as("a reconexão esvazia o cache por precaução")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("payload ilegível é descartado sem derrubar o ouvinte")
    void payload_ilegivel_nao_derruba_o_ouvinte() throws Exception {
        // Perder uma invalidação é ruim; perder todas as seguintes é muito pior.
        UUID a = criarEstabelecimento("Salão A");
        TenantContext.executar(a, () -> catalogo.buscar("s1"));

        try (var st = comoOwner().createStatement()) {
            st.execute("select pg_notify('" + InvalidadorDeCache.CANAL + "', 'isto nao e json')");
        }
        Thread.sleep(1_500);
        assertThat(ouvinte.conectado()).isTrue();

        notificarDeOutraConexao(a, "s1");
        aguardar(() -> {
            TenantContext.executar(a, () -> catalogo.buscar("s1"));
            return catalogo.consultas() == 2;
        }, "o ouvinte precisa seguir funcionando depois do payload inválido", 20);
    }

    private void notificarDeOutraConexao(UUID tenant, String chave) throws SQLException {
        String payload = "{\"estabelecimentoId\":\"" + tenant + "\",\"cache\":\"" + CACHE
                + "\",\"chave\":\"" + chave + "\"}";
        try (var ps = comoOwner().prepareStatement("select pg_notify(?, ?)")) {
            ps.setString(1, InvalidadorDeCache.CANAL);
            ps.setString(2, payload);
            ps.executeQuery().close();
        }
    }

    private void derrubarConexaoDoOuvinte() throws SQLException {
        try (var ps = comoOwner().prepareStatement("""
                select pg_terminate_backend(pid) from pg_stat_activity
                 where application_name = ?
                """)) {
            ps.setString(1, OuvinteDeInvalidacao.NOME_DA_CONEXAO);
            ps.executeQuery().close();
        }
    }

    private static void aguardar(BooleanSupplier condicao, String descricao, long segundos) {
        long limite = System.nanoTime() + Duration.ofSeconds(segundos).toNanos();
        while (System.nanoTime() < limite) {
            if (condicao.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError(descricao);
    }
}
