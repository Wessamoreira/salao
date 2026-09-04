package br.com.salao.agenda.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.agenda.api.OrigemDoAgendamento;
import br.com.salao.catalogo.api.TipoDeBloco;
import br.com.salao.catalogo.internal.application.CadastrarServicoUseCase;
import br.com.salao.catalogo.internal.application.EtapaBruta;
import br.com.salao.cliente.internal.application.CadastrarClienteUseCase;
import br.com.salao.cliente.internal.application.DadosDoCliente;
import br.com.salao.equipe.api.TipoDeVinculo;
import br.com.salao.equipe.internal.application.CadastrarProfissionalUseCase;
import br.com.salao.equipe.internal.application.DadosDoProfissional;
import br.com.salao.equipe.internal.application.DefinirJornadaUseCase;
import br.com.salao.equipe.internal.application.PeriodoBruto;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * RT-AGD-002 — a corrida.
 *
 * <p>A pré-checagem de conflito existe para dar erro bonito antes de gravar, e não para evitar a
 * corrida: entre ela e o insert cabe outra requisição. Quem decide é a exclusion constraint.
 *
 * <p>Este teste é o que prova a diferença. Sem ele, uma trava de aplicação passaria despercebida —
 * e ela não sobrevive a duas instâncias, que é justamente como o sistema roda em produção.
 */
@WithMockUser(authorities = { Permissao.USUARIO_GERENCIAR, Permissao.CONFIG_GERENCIAR,
        Permissao.CLIENTE_ESCREVER, Permissao.AGENDA_ESCREVER_TODAS })
class AgendamentoConcorrenteIT extends AbstractPostgresIT {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate DIA = LocalDate.now(SAO_PAULO).plusDays(7);

    /** Threads simultâneas precisam de conexões simultâneas; o pool padrão do teste é pequeno. */
    @DynamicPropertySource
    static void pool(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.hikari.maximum-pool-size", () -> "8");
    }

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarProfissionalUseCase cadastrarProfissional;
    @Autowired private DefinirJornadaUseCase definirJornada;
    @Autowired private CadastrarServicoUseCase cadastrarServico;
    @Autowired private CadastrarClienteUseCase cadastrarCliente;
    @Autowired private CriarAgendamentoUseCase criar;

    @Test
    @DisplayName("duas threads no mesmo horário: exatamente uma vence")
    void duas_threads_no_mesmo_horario_uma_falha() throws Exception {
        UUID tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        UUID ana = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional("Ana", null, "11144477735", TipoDeVinculo.EMPREGADO,
                        null, null, null, null, null, null))).id();
        TenantContext.executar(tenant, () -> definirJornada.executar(ana,
                Map.of(DIA.getDayOfWeek(),
                        List.of(new PeriodoBruto(LocalTime.of(9, 0), LocalTime.of(18, 0))))));
        UUID marilda = TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                new DadosDoCliente("Marilda", null, null, null, null)));
        UUID corte = TenantContext.obter(tenant, () -> cadastrarServico.executar("Corte", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 30)))).id();
        Instant asTreze = DIA.atTime(LocalTime.of(13, 0)).atZone(SAO_PAULO).toInstant();

        var largada = new CountDownLatch(1);
        var terminaram = new CountDownLatch(2);
        var venceram = new AtomicInteger();
        var perderam = new AtomicInteger();
        var motivos = new java.util.concurrent.ConcurrentLinkedQueue<String>();

        // O SecurityContext de @WithMockUser vive na thread do teste e NÃO é herdado pelas do
        // pool. Sem levá-lo à mão, as duas threads seriam negadas por @PreAuthorize e o teste
        // passaria a provar outra coisa — foi exatamente o que aconteceu na primeira execução.
        var contexto = org.springframework.security.core.context.SecurityContextHolder
                .getContext();

        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                executor.submit(() -> {
                    org.springframework.security.core.context.SecurityContextHolder
                            .setContext(contexto);
                    try {
                        largada.await();
                        TenantContext.executar(tenant, () -> criar.executar(new NovoAgendamento(
                                marilda, ana, corte, asTreze, OrigemDoAgendamento.PAINEL, null,
                                null, false, false, null, null)));
                        venceram.incrementAndGet();
                    } catch (Exception e) {
                        perderam.incrementAndGet();
                        motivos.add(e instanceof br.com.salao.shared.erro.ErroDeDominio erro
                                ? erro.codigo().codigo()
                                : e.getClass().getSimpleName() + ": " + e.getMessage());
                    } finally {
                        org.springframework.security.core.context.SecurityContextHolder
                                .clearContext();
                        terminaram.countDown();
                    }
                });
            }
            largada.countDown();
            assertThat(terminaram.await(30, TimeUnit.SECONDS)).isTrue();
        }

        // A garantia que NUNCA pode falhar, e a única que não depende de as duas threads terem
        // mesmo se cruzado: o banco jamais deixa passar duas. Se esta quebrar, a constraint está
        // errada — e nenhuma corrida "sortuda" esconde isso.
        assertThat(quantosBlocos(tenant))
                .as("o banco jamais aceita duas reservas; resultados: %s", motivos)
                .isEqualTo(1);

        // As demais afirmam a corrida em si. Toda uma carrega os motivos: esta classe já falhou
        // uma vez sob a suíte completa e o relatório foi sobrescrito antes de ser lido, o que
        // custou a investigação inteira. Uma asserção que não diz o que aconteceu é uma
        // investigação adiada.
        assertThat(venceram.get()).as("exatamente uma vence; resultados: %s", motivos).isEqualTo(1);
        assertThat(perderam.get()).as("exatamente uma perde; resultados: %s", motivos)
                .isEqualTo(1);
        assertThat(motivos)
                .as("a perdedora perde pelo conflito do banco, não por outra coisa")
                .containsExactly("ER-AGD-CONFLITO_HORARIO");
    }

    private int quantosBlocos(UUID tenant) throws SQLException {
        try (var ps = comoOwner().prepareStatement(
                "select count(*) from agendamento_bloco where estabelecimento_id = ?")) {
            ps.setObject(1, tenant);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
