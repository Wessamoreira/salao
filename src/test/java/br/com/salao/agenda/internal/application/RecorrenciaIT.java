package br.com.salao.agenda.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.agenda.api.AgendamentoResumo;
import br.com.salao.agenda.api.Frequencia;
import br.com.salao.agenda.api.MotivoDoBloqueioDeAgenda;
import br.com.salao.agenda.api.OrigemDoAgendamento;
import br.com.salao.agenda.api.StatusAgendamento;
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
import br.com.salao.shared.erro.ErroDeDominio;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-AGD-009 — recorrência. */
@WithMockUser(authorities = { Permissao.USUARIO_GERENCIAR, Permissao.CONFIG_GERENCIAR,
        Permissao.CLIENTE_ESCREVER, Permissao.AGENDA_LER_TODAS,
        Permissao.AGENDA_ESCREVER_TODAS })
class RecorrenciaIT extends AbstractPostgresIT {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate PRIMEIRA = LocalDate.now(SAO_PAULO).plusDays(7);

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarProfissionalUseCase cadastrarProfissional;
    @Autowired private DefinirJornadaUseCase definirJornada;
    @Autowired private CadastrarServicoUseCase cadastrarServico;
    @Autowired private CadastrarClienteUseCase cadastrarCliente;
    @Autowired private CriarRecorrenciaUseCase criarSerie;
    @Autowired private EncerrarRecorrenciaUseCase encerrar;
    @Autowired private CriarAgendamentoUseCase criar;
    @Autowired private BloquearGradeUseCase bloquear;
    @Autowired private ConsultarAgendamentoUseCase consultar;

    private UUID tenant;
    private UUID ana;
    private UUID marilda;
    private UUID escova;

    private void cenario() {
        tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        ana = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional("Ana", null, "11144477735", TipoDeVinculo.EMPREGADO,
                        null, null, null, null, null, null))).id();
        TenantContext.executar(tenant, () -> definirJornada.executar(ana,
                Map.of(PRIMEIRA.getDayOfWeek(),
                        List.of(new PeriodoBruto(LocalTime.of(9, 0), LocalTime.of(18, 0))))));
        marilda = TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                new DadosDoCliente("Marilda", null, null, null, null)));
        escova = TenantContext.obter(tenant, () -> cadastrarServico.executar("Escova", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 45)))).id();
    }

    private Instant naSemana(int semanas, int hora) {
        return PRIMEIRA.plusWeeks(semanas).atTime(LocalTime.of(hora, 0))
                .atZone(SAO_PAULO).toInstant();
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ o caso da rotina

    @Test
    @DisplayName("toda quinta às 14h: quatro ocorrências, uma por semana")
    void serie_semanal() {
        cenario();

        var serie = TenantContext.obter(tenant, () -> criarSerie.executar(marilda, ana, escova,
                naSemana(0, 14), Frequencia.SEMANAL, 4, "cliente semanal"));

        assertThat(serie.marcadas()).isEqualTo(4);
        assertThat(serie.naoCouberam()).isEmpty();
        assertThat(serie.agendamentos()).hasSize(4);
        assertThat(inicios(serie.agendamentos())).containsExactly(
                naSemana(0, 14), naSemana(1, 14), naSemana(2, 14), naSemana(3, 14));
    }

    @Test
    @DisplayName("quinzenal pula uma semana")
    void serie_quinzenal() {
        cenario();

        var serie = TenantContext.obter(tenant, () -> criarSerie.executar(marilda, ana, escova,
                naSemana(0, 14), Frequencia.QUINZENAL, 3, null));

        assertThat(inicios(serie.agendamentos())).containsExactly(
                naSemana(0, 14), naSemana(2, 14), naSemana(4, 14));
    }

    @Test
    @DisplayName("as ocorrências nascem CONFIRMADAS e ligadas à série")
    void ocorrencias_confirmadas_e_ligadas() throws SQLException {
        // Origem RECORRENCIA nasce confirmada (RN-AGD-013): a cliente já combinou a série inteira.
        cenario();
        var serie = TenantContext.obter(tenant, () -> criarSerie.executar(marilda, ana, escova,
                naSemana(0, 14), Frequencia.SEMANAL, 2, null));

        assertThat(TenantContext.obter(tenant,
                () -> consultar.porId(serie.agendamentos().getFirst())).status())
                .isEqualTo(StatusAgendamento.CONFIRMADO);
        assertThat(quantosLigadosA(serie.recorrencia().id())).isEqualTo(2);
    }

    // ------------------------------------------------------------------ a decisão oposta

    @Test
    @DisplayName("marca o que cabe e devolve o que não coube")
    void parcial_e_o_certo_aqui() {
        // É o oposto de RT-AGD-008, e as duas parecem a mesma pergunta. Lá o empurrão é UM ato — o
        // dia andou — e metade é pior que nada. Aqui cada ocorrência é um compromisso
        // independente: a terceira quinta é feriado, e recusar as outras três seria absurdo.
        cenario();
        TenantContext.executar(tenant, () -> bloquear.executar(ana, null,
                MotivoDoBloqueioDeAgenda.FERIADO, "feriado", naSemana(2, 9), naSemana(2, 18)));

        var serie = TenantContext.obter(tenant, () -> criarSerie.executar(marilda, ana, escova,
                naSemana(0, 14), Frequencia.SEMANAL, 4, null));

        assertThat(serie.marcadas()).as("três das quatro").isEqualTo(3);
        assertThat(serie.naoCouberam()).containsExactly(naSemana(2, 14));
    }

    @Test
    @DisplayName("uma recusa não derruba a transação das outras")
    void recusa_nao_envenena_a_transacao() {
        // O defeito que este desenho quase teve: numa transação única, o ErroDeDominio de uma
        // ocorrência marcaria tudo como rollback-only — e a violação da exclusion constraint
        // aborta a transação no PostgreSQL, deixando os comandos seguintes sem efeito. O
        // resultado seria "não marca nada" com aparência de "marcou três".
        cenario();
        // Duas semanas tomadas, no meio da série.
        TenantContext.executar(tenant, () -> criar.executar(new NovoAgendamento(
                marilda, ana, escova, naSemana(1, 14), OrigemDoAgendamento.PAINEL, null, null,
                false, false, null, null)));
        TenantContext.executar(tenant, () -> criar.executar(new NovoAgendamento(
                marilda, ana, escova, naSemana(3, 14), OrigemDoAgendamento.PAINEL, null, null,
                false, false, null, null)));

        var serie = TenantContext.obter(tenant, () -> criarSerie.executar(marilda, ana, escova,
                naSemana(0, 14), Frequencia.SEMANAL, 5, null));

        assertThat(serie.marcadas()).as("as três livres foram marcadas mesmo assim").isEqualTo(3);
        assertThat(serie.naoCouberam()).containsExactly(naSemana(1, 14), naSemana(3, 14));
        assertThat(inicios(serie.agendamentos()))
                .containsExactly(naSemana(0, 14), naSemana(2, 14), naSemana(4, 14));
    }

    @Test
    @DisplayName("série inteira sem espaço devolve zero, não erro")
    void nenhuma_coube() {
        cenario();
        TenantContext.executar(tenant, () -> bloquear.executar(ana, null,
                MotivoDoBloqueioDeAgenda.FERIAS, "férias", naSemana(0, 9), naSemana(3, 18)));

        var serie = TenantContext.obter(tenant, () -> criarSerie.executar(marilda, ana, escova,
                naSemana(0, 14), Frequencia.SEMANAL, 3, null));

        assertThat(serie.marcadas()).isZero();
        assertThat(serie.naoCouberam()).hasSize(3);
        assertThat(serie.recorrencia().ativa()).as("a série existe, esperando ser resolvida")
                .isTrue();
    }

    // ------------------------------------------------------------------ recusas de entrada

    @Test
    @DisplayName("uma ocorrência só não é série")
    void minimo_de_duas() {
        cenario();

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> criarSerie.executar(marilda, ana, escova, naSemana(0, 14),
                        Frequencia.SEMANAL, 1, null)));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-RECORRENCIA_INVALIDA");
    }

    @Test
    @DisplayName("mais de um ano é engano, e materializar 500 linhas por engano dói")
    void limite_de_ocorrencias() {
        cenario();

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> criarSerie.executar(marilda, ana, escova, naSemana(0, 14),
                        Frequencia.SEMANAL, 80, null)));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-RECORRENCIA_INVALIDA");
        assertThat(erro).hasMessageContaining("um ano");
    }

    // ------------------------------------------------------------------ encerrar

    @Test
    @DisplayName("encerrar cancela o futuro e deixa o passado como está")
    void encerrar_cancela_o_futuro() throws SQLException {
        cenario();
        var serie = TenantContext.obter(tenant, () -> criarSerie.executar(marilda, ana, escova,
                naSemana(0, 14), Frequencia.SEMANAL, 3, null));

        int canceladas = TenantContext.obter(tenant,
                () -> encerrar.executar(serie.recorrencia().id(), "cliente mudou de cidade"));

        assertThat(canceladas).isEqualTo(3);
        assertThat(TenantContext.obter(tenant,
                () -> consultar.porId(serie.agendamentos().getFirst())).status())
                .isEqualTo(StatusAgendamento.CANCELADO);
        assertThat(serieAtiva(serie.recorrencia().id())).isFalse();
    }

    @Test
    @DisplayName("cada cancelamento é um cancelamento de verdade, com motivo")
    void cancelamentos_com_motivo() throws SQLException {
        // Apagar as linhas em massa seria mais rápido e perderia o que RT-AGD-005 existe para
        // guardar — o histórico da cliente diria que ela nunca teve aquele horário.
        cenario();
        var serie = TenantContext.obter(tenant, () -> criarSerie.executar(marilda, ana, escova,
                naSemana(0, 14), Frequencia.SEMANAL, 2, null));

        TenantContext.executar(tenant,
                () -> encerrar.executar(serie.recorrencia().id(), "cliente mudou de cidade"));

        try (var ps = comoOwner().prepareStatement(
                "select motivo_cancelamento from agendamento where recorrencia_id = ?")) {
            ps.setObject(1, serie.recorrencia().id());
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    assertThat(rs.getString(1)).isEqualTo("cliente mudou de cidade");
                }
            }
        }
    }

    @Test
    @DisplayName("encerrar sem motivo é recusado")
    void encerrar_exige_motivo() {
        cenario();
        var serie = TenantContext.obter(tenant, () -> criarSerie.executar(marilda, ana, escova,
                naSemana(0, 14), Frequencia.SEMANAL, 2, null));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> encerrar.executar(serie.recorrencia().id(), " ")));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-MOTIVO_OBRIGATORIO");
    }

    @Test
    @DisplayName("série de outro salão não é encontrada")
    void isolamento_entre_saloes() {
        cenario();
        var serie = TenantContext.obter(tenant, () -> criarSerie.executar(marilda, ana, escova,
                naSemana(0, 14), Frequencia.SEMANAL, 2, null));
        UUID salaoB = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Outra",
                "outra-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        Throwable erro = catchThrowable(() -> TenantContext.executar(salaoB,
                () -> encerrar.executar(serie.recorrencia().id(), "engano")));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-RECORRENCIA_NAO_ENCONTRADA");
    }

    // ------------------------------------------------------------------ auxiliares

    private List<Instant> inicios(List<UUID> agendamentos) {
        return agendamentos.stream()
                .map(id -> TenantContext.obter(tenant, () -> consultar.porId(id)))
                .map(AgendamentoResumo::inicio)
                .toList();
    }

    private int quantosLigadosA(UUID recorrenciaId) throws SQLException {
        try (var ps = comoOwner().prepareStatement(
                "select count(*) from agendamento where recorrencia_id = ?")) {
            ps.setObject(1, recorrenciaId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private boolean serieAtiva(UUID recorrenciaId) throws SQLException {
        try (var ps = comoOwner().prepareStatement(
                "select ativa from recorrencia where id = ?")) {
            ps.setObject(1, recorrenciaId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }
}
