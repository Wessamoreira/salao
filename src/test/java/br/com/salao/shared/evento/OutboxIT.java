package br.com.salao.shared.evento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** RT-INF-006 — outbox transacional. */
class OutboxIT extends AbstractPostgresIT {

    record EventoDeTeste(UUID estabelecimentoId, String dado) implements EventoDeDominio {
    }

    /**
     * Ouvinte de teste. {@code @ApplicationModuleListener} é o que exercita o caminho real:
     * assíncrono, transacional e depois do commit.
     *
     * <p><strong>Todo o estado é lido por método, nunca por campo.</strong> As anotações fazem o
     * Spring envolver este bean num proxy CGLIB, e o proxy é instanciado sem passar pelo
     * construtor — seus campos ficam nos valores padrão. Ler {@code ouvinte.recebido} devolveria
     * {@code null} mesmo com o alvo inicializado. Chamada de método é delegada ao alvo; leitura de
     * campo, não.
     */
    static class OuvinteDeTeste {
        private final AtomicReference<UUID> tenantVisto = new AtomicReference<>();
        private final AtomicInteger invocacoes = new AtomicInteger();
        private volatile CountDownLatch recebido = new CountDownLatch(1);
        private volatile boolean falhar;

        @ApplicationModuleListener
        void aoReceber(EventoDeTeste evento) {
            invocacoes.incrementAndGet();
            if (falhar) {
                recebido.countDown();
                throw new IllegalStateException("falha proposital do ouvinte");
            }
            tenantVisto.set(TenantContext.atual());
            recebido.countDown();
        }

        boolean aguardarRecebimento(long segundos) throws InterruptedException {
            return recebido.await(segundos, TimeUnit.SECONDS);
        }

        UUID tenantVisto() {
            return tenantVisto.get();
        }

        int invocacoes() {
            return invocacoes.get();
        }

        void falharNaProxima() {
            falhar = true;
        }

        void reiniciar() {
            tenantVisto.set(null);
            invocacoes.set(0);
            recebido = new CountDownLatch(1);
            falhar = false;
        }
    }

    @TestConfiguration
    static class Configuracao {
        @Bean
        OuvinteDeTeste ouvinteDeTeste() {
            return new OuvinteDeTeste();
        }
    }

    @Autowired
    private OuvinteDeTeste ouvinte;

    @Autowired
    private ApplicationEventPublisher publicador;

    @Autowired
    private PlatformTransactionManager gerenciadorDeTransacao;

    @Autowired
    private ReenviadorDeEventos reenviador;

    @Autowired
    private PurgadorDoOutbox purgador;

    @Autowired
    private MetricasDoOutbox metricas;

    private TransactionTemplate tx;

    @BeforeEach
    void prepararOutbox() throws SQLException {
        tx = new TransactionTemplate(gerenciadorDeTransacao);
        ouvinte.reiniciar();
        try (var st = comoOwner().createStatement()) {
            st.execute("truncate event_publication, event_publication_archive");
        }
    }

    @Test
    @DisplayName("evento publicado numa transação que commita chega ao ouvinte com o tenant certo")
    void evento_entregue_com_tenant_restaurado() throws Exception {
        UUID tenant = criarEstabelecimento("Salão A");

        TenantContext.executar(tenant, () ->
                tx.executeWithoutResult(s ->
                        publicador.publishEvent(new EventoDeTeste(tenant, "confirmar"))));

        assertThat(ouvinte.aguardarRecebimento(20))
                .as("o ouvinte assíncrono precisa ser invocado")
                .isTrue();
        assertThat(ouvinte.tenantVisto())
                .as("o tenant atravessou a fronteira de thread via PropagadorDeTenant")
                .isEqualTo(tenant);
        aguardarPendentes(0);
    }

    @Test
    @DisplayName("transação que falha não entrega o evento nem deixa rastro no outbox")
    void rollback_nao_entrega() throws Exception {
        // É a razão de o outbox existir: sem ele, o WhatsApp sairia anunciando um agendamento
        // que o commit desfez.
        UUID tenant = criarEstabelecimento("Salão A");

        assertThatThrownBy(() -> TenantContext.executar(tenant, () ->
                tx.executeWithoutResult(s -> {
                    publicador.publishEvent(new EventoDeTeste(tenant, "confirmar"));
                    throw new IllegalStateException("negócio falhou depois de publicar");
                })))
                .isInstanceOf(IllegalStateException.class);

        assertThat(ouvinte.aguardarRecebimento(3))
                .as("nada deve ser entregue")
                .isFalse();
        assertThat(ouvinte.invocacoes()).isZero();
        assertThat(pendentes()).isZero();
    }

    @Test
    @DisplayName("ouvinte que falha deixa a publicação pendente, e o reenviador a reentrega")
    void pendencia_e_reenviada() throws Exception {
        UUID tenant = criarEstabelecimento("Salão A");
        ouvinte.falharNaProxima();

        TenantContext.executar(tenant, () ->
                tx.executeWithoutResult(s ->
                        publicador.publishEvent(new EventoDeTeste(tenant, "confirmar"))));

        assertThat(ouvinte.aguardarRecebimento(20)).isTrue();
        aguardarPendentes(1);   // a falha não some: fica registrada
        assertThat(pendentes()).isEqualTo(1);
        assertThat(metricas.pendentes()).isEqualTo(1);
        assertThat(metricas.idadeDaMaisAntiga()).isGreaterThanOrEqualTo(0);

        ouvinte.reiniciar();
        // Idade zero: no teste não há motivo para esperar a janela de proteção contra
        // competir com uma entrega ainda em curso.
        reenviador.executar(Duration.ZERO);

        assertThat(ouvinte.aguardarRecebimento(20))
                .as("o reenviador precisa abrir o escopo do tenant a partir do payload")
                .isTrue();
        assertThat(ouvinte.tenantVisto()).isEqualTo(tenant);
    }

    @Test
    @DisplayName("o reenviador não toca em pendência de outro estabelecimento")
    void reenvio_e_por_tenant() throws Exception {
        UUID a = criarEstabelecimento("Salão A");
        UUID b = criarEstabelecimento("Salão B");
        inserirPendencia(b);

        ouvinte.falharNaProxima();
        TenantContext.executar(a, () ->
                tx.executeWithoutResult(s -> publicador.publishEvent(new EventoDeTeste(a, "x"))));
        assertThat(ouvinte.aguardarRecebimento(20)).isTrue();

        ouvinte.reiniciar();
        reenviador.executar(Duration.ZERO);
        assertThat(ouvinte.aguardarRecebimento(20)).isTrue();

        // Só a do outro estabelecimento sobra: a de A foi reenviada e concluída.
        aguardarPendentes(1);
    }

    @Test
    @DisplayName("a purga remove arquivo vencido e preserva o recente")
    void purga_do_arquivo() throws Exception {
        inserirArquivada(Duration.ofDays(30));
        inserirArquivada(Duration.ofDays(1));

        int removidos = purgador.executar(Duration.ofDays(14));

        assertThat(removidos).isEqualTo(1);
        assertThat(contar("event_publication_archive")).isEqualTo(1);
    }

    /**
     * O latch é acionado <em>dentro</em> do ouvinte — antes de a transação dele commitar e de o
     * Modulith marcar a publicação como concluída. Assumir que o contador já mudou nesse instante
     * é corrida, e corrida em teste vira intermitência que custa horas depois. Espera-se a
     * condição, com teto.
     */
    private void aguardarPendentes(long esperado) {
        long limite = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        long ultimo = -1;
        while (System.nanoTime() < limite) {
            try {
                ultimo = pendentes();
                if (ultimo == esperado) {
                    return;
                }
                Thread.sleep(50);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError(
                "esperava " + esperado + " publicações pendentes; última leitura: " + ultimo);
    }

    private long pendentes() throws SQLException {
        try (var st = comoOwner().createStatement();
             var rs = st.executeQuery(
                     "select count(*) from event_publication where completion_date is null")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long contar(String tabela) throws SQLException {
        try (var st = comoOwner().createStatement();
             var rs = st.executeQuery("select count(*) from " + tabela)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void inserirPendencia(UUID tenant) throws SQLException {
        try (var ps = comoOwner().prepareStatement("""
                insert into event_publication
                    (id, listener_id, event_type, serialized_event, publication_date)
                values (gen_random_uuid(), 'ouvinte-de-outro-tenant', ?, ?, now())
                """)) {
            ps.setString(1, EventoDeTeste.class.getName());
            ps.setString(2, "{\"estabelecimentoId\":\"" + tenant + "\",\"dado\":\"x\"}");
            ps.executeUpdate();
        }
    }

    private void inserirArquivada(Duration idade) throws SQLException {
        try (var ps = comoOwner().prepareStatement("""
                insert into event_publication_archive
                    (id, listener_id, event_type, serialized_event,
                     publication_date, completion_date)
                values (gen_random_uuid(), 'ouvinte', 'X', '{}',
                        now() - cast(? as interval), now() - cast(? as interval))
                """)) {
            ps.setString(1, idade.toSeconds() + " seconds");
            ps.setString(2, idade.toSeconds() + " seconds");
            ps.executeUpdate();
        }
    }
}
