package br.com.salao.shared.idempotencia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** RT-INF-005 — idempotência de escrita. */
class IdempotenciaIT extends AbstractPostgresIT {

    /** Precisa de mais de uma conexão: o teste de concorrência usa duas transações simultâneas. */
    @DynamicPropertySource
    static void poolParaConcorrencia(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    }

    record Resposta(UUID id, String nome) {
    }

    @Autowired
    private Idempotencia idempotencia;

    @Autowired
    private PurgadorDeIdempotencia purgador;

    @Autowired
    private DataSource dataSource;

    private ChaveDeIdempotencia chave(String valor) {
        return new ChaveDeIdempotencia("criar-agendamento", valor);
    }

    @Test
    @DisplayName("a segunda chamada com a mesma chave não reexecuta a ação")
    void segunda_chamada_repete_sem_reexecutar() throws SQLException {
        UUID tenant = criarEstabelecimento("Salão A");
        var execucoes = new AtomicInteger();
        var payload = new Resposta(UUID.randomUUID(), "mechas");

        var primeira = TenantContext.obter(tenant, () -> idempotencia.executar(
                chave("k-1"), payload, Resposta.class,
                () -> { execucoes.incrementAndGet(); return payload; }));

        var segunda = TenantContext.obter(tenant, () -> idempotencia.executar(
                chave("k-1"), payload, Resposta.class,
                () -> { execucoes.incrementAndGet(); return payload; }));

        assertThat(execucoes).hasValue(1);
        assertThat(primeira.repeticao()).isFalse();
        assertThat(segunda.repeticao()).isTrue();
        assertThat(segunda.valor()).isEqualTo(primeira.valor());
    }

    @Test
    @DisplayName("mesma chave com payload diferente é conflito, não repetição")
    void payload_diferente_e_conflito() throws SQLException {
        // Reexecutar aqui criaria um segundo agendamento sob a chave do primeiro.
        UUID tenant = criarEstabelecimento("Salão A");
        var original = new Resposta(UUID.randomUUID(), "mechas");
        var outro = new Resposta(UUID.randomUUID(), "escova");

        TenantContext.obter(tenant, () -> idempotencia.executar(
                chave("k-1"), original, Resposta.class, () -> original));

        assertThatThrownBy(() -> TenantContext.obter(tenant, () -> idempotencia.executar(
                chave("k-1"), outro, Resposta.class, () -> outro)))
                .isInstanceOf(ErroDeDominio.class)
                .extracting(e -> ((ErroDeDominio) e).codigo().codigo())
                .isEqualTo("ER-INF-IDEMPOTENCIA_CONFLITO");
    }

    @Test
    @DisplayName("escopos diferentes não colidem com a mesma chave")
    void escopos_diferentes_nao_colidem() throws SQLException {
        UUID tenant = criarEstabelecimento("Salão A");
        var execucoes = new AtomicInteger();
        var payload = new Resposta(UUID.randomUUID(), "x");

        TenantContext.obter(tenant, () -> idempotencia.executar(
                new ChaveDeIdempotencia("criar-agendamento", "k-1"), payload, Resposta.class,
                () -> { execucoes.incrementAndGet(); return payload; }));
        TenantContext.obter(tenant, () -> idempotencia.executar(
                new ChaveDeIdempotencia("cancelar-agendamento", "k-1"), payload, Resposta.class,
                () -> { execucoes.incrementAndGet(); return payload; }));

        assertThat(execucoes).as("são operações distintas").hasValue(2);
    }

    @Test
    @DisplayName("a mesma chave em outro estabelecimento é outra operação")
    void chave_e_por_tenant() throws SQLException {
        UUID a = criarEstabelecimento("Salão A");
        UUID b = criarEstabelecimento("Salão B");
        var execucoes = new AtomicInteger();
        var payload = new Resposta(UUID.randomUUID(), "x");

        TenantContext.obter(a, () -> idempotencia.executar(chave("k-1"), payload, Resposta.class,
                () -> { execucoes.incrementAndGet(); return payload; }));
        TenantContext.obter(b, () -> idempotencia.executar(chave("k-1"), payload, Resposta.class,
                () -> { execucoes.incrementAndGet(); return payload; }));

        assertThat(execucoes).hasValue(2);
    }

    @Test
    @DisplayName("ação que falha não consome a chave")
    void falha_no_negocio_libera_a_chave() throws SQLException {
        // O registro e o efeito estão na MESMA transação: se o negócio falha, o registro
        // desaparece junto. Num desenho de reserva separada, a chave ficaria queimada e o
        // cliente não conseguiria repetir a operação que nunca aconteceu.
        UUID tenant = criarEstabelecimento("Salão A");
        var payload = new Resposta(UUID.randomUUID(), "x");

        assertThatThrownBy(() -> TenantContext.obter(tenant, () -> idempotencia.executar(
                chave("k-1"), payload, Resposta.class,
                () -> { throw new IllegalStateException("falhou o negócio"); })))
                .isInstanceOf(IllegalStateException.class);

        var depois = TenantContext.obter(tenant, () -> idempotencia.executar(
                chave("k-1"), payload, Resposta.class, () -> payload));

        assertThat(depois.repeticao()).as("a chave ficou livre").isFalse();
        assertThat(depois.valor()).isEqualTo(payload);
    }

    @Test
    @DisplayName("requisições simultâneas: a segunda espera no índice e repete")
    void concorrencia_e_arbitrada_pelo_banco() throws Exception {
        // A garantia é do Postgres, não da aplicação: a segunda transação BLOQUEIA na unique
        // até a primeira commitar. Lock de aplicação não sobreviveria a duas instâncias.
        assertThat(((HikariDataSource) dataSource).getMaximumPoolSize())
                .as("o teste precisa de duas conexões simultâneas")
                .isGreaterThan(1);

        UUID tenant = criarEstabelecimento("Salão A");
        var payload = new Resposta(UUID.randomUUID(), "x");
        var execucoes = new AtomicInteger();
        var dentroDaAcao = new CountDownLatch(1);
        var segundaVaiTentar = new CountDownLatch(1);

        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResultadoIdempotente<Resposta>> primeira = executor.submit(() ->
                    TenantContext.obter(tenant, () -> idempotencia.executar(
                            chave("k-1"), payload, Resposta.class, () -> {
                                execucoes.incrementAndGet();
                                dentroDaAcao.countDown();
                                aguardar(segundaVaiTentar);
                                // margem para a segunda realmente bloquear no índice
                                dormir(500);
                                return payload;
                            })));

            assertThat(dentroDaAcao.await(10, TimeUnit.SECONDS)).isTrue();

            Future<ResultadoIdempotente<Resposta>> segunda = executor.submit(() -> {
                segundaVaiTentar.countDown();
                return TenantContext.obter(tenant, () -> idempotencia.executar(
                        chave("k-1"), payload, Resposta.class, () -> {
                            execucoes.incrementAndGet();
                            return payload;
                        }));
            });

            assertThat(primeira.get(30, TimeUnit.SECONDS).repeticao()).isFalse();
            assertThat(segunda.get(30, TimeUnit.SECONDS).repeticao()).isTrue();
            assertThat(execucoes).as("o efeito de negócio aconteceu uma única vez").hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("a purga remove vencidos e preserva válidos, pela role de manutenção")
    void purga_remove_apenas_vencidos() throws SQLException {
        UUID a = criarEstabelecimento("Salão A");
        UUID b = criarEstabelecimento("Salão B");
        inserirRegistro(a, "vencida", -1);
        inserirRegistro(b, "vencida-outro-tenant", -1);
        inserirRegistro(a, "valida", 7);

        int removidos = purgador.executar();

        assertThat(removidos)
                .as("manutenção alcança todos os estabelecimentos; a aplicação, nenhum além do seu")
                .isEqualTo(2);
        assertThat(contarRegistros()).isEqualTo(1);
    }

    private void inserirRegistro(UUID tenant, String chave, int diasAteExpirar)
            throws SQLException {
        try (var ps = comoOwner().prepareStatement("""
                insert into idempotencia
                    (estabelecimento_id, escopo, chave, hash_payload, expira_em)
                values (?, 'teste', ?, 'hash', now() + make_interval(days => ?))
                """)) {
            ps.setObject(1, tenant);
            ps.setString(2, chave);
            ps.setInt(3, diasAteExpirar);
            ps.executeUpdate();
        }
    }

    private long contarRegistros() throws SQLException {
        try (var st = comoOwner().createStatement();
             var rs = st.executeQuery("select count(*) from idempotencia")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void aguardar(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch não liberou");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void dormir(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
