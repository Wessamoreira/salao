package br.com.salao.agenda.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.agenda.api.AgendamentoResumo;
import br.com.salao.agenda.api.NoShowRegistrado;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/** RT-AGD-006 — registrar no-show. */
@WithMockUser(authorities = { Permissao.USUARIO_GERENCIAR, Permissao.CONFIG_GERENCIAR,
        Permissao.CLIENTE_ESCREVER, Permissao.AGENDA_LER_TODAS,
        Permissao.AGENDA_ESCREVER_TODAS })
@RecordApplicationEvents
class RegistrarNoShowIT extends AbstractPostgresIT {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate ONTEM = LocalDate.now(SAO_PAULO).minusDays(1);
    private static final LocalDate DAQUI_A_UMA_SEMANA = LocalDate.now(SAO_PAULO).plusDays(7);

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarProfissionalUseCase cadastrarProfissional;
    @Autowired private DefinirJornadaUseCase definirJornada;
    @Autowired private CadastrarServicoUseCase cadastrarServico;
    @Autowired private CadastrarClienteUseCase cadastrarCliente;
    @Autowired private CriarAgendamentoUseCase criar;
    @Autowired private RegistrarNoShowUseCase registrarFalta;
    @Autowired private CancelarAgendamentoUseCase cancelar;
    @Autowired private ConsultarAgendamentoUseCase consultar;
    @Autowired private ApplicationEvents eventos;

    private UUID tenant;
    private UUID ana;
    private UUID marilda;
    private UUID mechas;

    private void cenario() {
        tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        ana = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional("Ana", null, "11144477735", TipoDeVinculo.EMPREGADO,
                        null, null, null, null, null, null))).id();
        // Jornada nos dois dias que os testes usam.
        TenantContext.executar(tenant, () -> definirJornada.executar(ana, Map.of(
                ONTEM.getDayOfWeek(),
                List.of(new PeriodoBruto(LocalTime.of(9, 0), LocalTime.of(18, 0))),
                DAQUI_A_UMA_SEMANA.getDayOfWeek(),
                List.of(new PeriodoBruto(LocalTime.of(9, 0), LocalTime.of(18, 0))))));
        marilda = TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                new DadosDoCliente("Marilda", null, null, null, null)));
        // Coloração: 30 ativo, 40 pausa, 30 ativo. 100 minutos totais, 60 do profissional.
        mechas = TenantContext.obter(tenant, () -> cadastrarServico.executar("Mechas", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 30),
                        new EtapaBruta(TipoDeBloco.PAUSA, 40),
                        new EtapaBruta(TipoDeBloco.ATIVO, 30)))).id();
    }

    private Instant as(LocalDate data, int hora) {
        return data.atTime(LocalTime.of(hora, 0)).atZone(SAO_PAULO).toInstant();
    }

    /** Um horário de ontem, lançado com forcarPassado — é o que dá o que marcar como falta. */
    private AgendamentoResumo deOntem(int hora) {
        return TenantContext.obter(tenant, () -> criar.executar(new NovoAgendamento(
                marilda, ana, mechas, as(ONTEM, hora), OrigemDoAgendamento.PAINEL, null, null,
                false, true, "lançando o que ficou no caderno", null)));
    }

    private AgendamentoResumo daSemanaQueVem(int hora) {
        return TenantContext.obter(tenant, () -> criar.executar(new NovoAgendamento(
                marilda, ana, mechas, as(DAQUI_A_UMA_SEMANA, hora), OrigemDoAgendamento.PAINEL,
                null, null, false, false, null, null)));
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ a regra da rotina

    @Test
    @DisplayName("não se falta a um compromisso que ainda não começou")
    void nao_marca_falta_no_futuro() {
        // É a regra que distingue esta rotina do cancelamento. Cancelar é sobre o futuro — a
        // cliente avisa que não vem. No-show é sobre o passado: ela não veio, e isso só se sabe
        // depois da hora. Marcar antes registraria uma ausência que não aconteceu, num status
        // terminal que não teria como ser desfeito quando a cliente chegasse.
        cenario();
        var futuro = daSemanaQueVem(13);

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> registrarFalta.executar(futuro.id(), futuro.versao())));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-AINDA_NAO_COMECOU");
        assertThat(TenantContext.obter(tenant, () -> consultar.porId(futuro.id())).status())
                .as("a recusa não deixou estado pela metade")
                .isEqualTo(StatusAgendamento.CONFIRMADO);
    }

    @Test
    @DisplayName("basta ter começado, não terminado")
    void basta_ter_comecado() {
        // Às 13h15 de um horário das 13h, a recepção já sabe. Exigir o fim faria o salão esperar
        // uma hora e meia para registrar o evidente — e o resto do horário, que poderia receber um
        // encaixe, ficaria bloqueado esse tempo todo.
        cenario();
        var comecou = deOntem(13);

        var falta = TenantContext.obter(tenant,
                () -> registrarFalta.executar(comecou.id(), comecou.versao()));

        assertThat(falta.status()).isEqualTo(StatusAgendamento.NO_SHOW);
    }

    // ------------------------------------------------------------------ o efeito

    @Test
    @DisplayName("a falta libera o horário, como o cancelamento")
    void falta_libera_o_horario() throws SQLException {
        cenario();
        var faltou = deOntem(13);

        TenantContext.executar(tenant,
                () -> registrarFalta.executar(faltou.id(), faltou.versao()));

        assertThat(statusDosBlocos(faltou.id()))
                .as("os blocos acompanham a cabeça (RN-AGD-006)")
                .containsExactly("NO_SHOW", "NO_SHOW", "NO_SHOW");
        assertThat(StatusAgendamento.NO_SHOW.ocupaAAgenda()).isFalse();
    }

    @Test
    @DisplayName("o evento conta os minutos do PROFISSIONAL, não a duração total")
    void minutos_perdidos_descontam_a_pausa() {
        // Numa coloração de 100 minutos, 40 eram pausa e a profissional estaria livre de qualquer
        // jeito. Contar os 100 inflaria o prejuízo e tiraria a credibilidade do número justamente
        // na conversa em que ele precisa ser levado a sério.
        cenario();
        var faltou = deOntem(13);

        TenantContext.executar(tenant,
                () -> registrarFalta.executar(faltou.id(), faltou.versao()));

        assertThat(eventos.stream(NoShowRegistrado.class).toList()).singleElement()
                .satisfies(evento -> {
                    assertThat(evento.minutosPerdidos())
                            .as("60 de trabalho, não os 100 do relógio").isEqualTo(60);
                    assertThat(evento.clienteId()).isEqualTo(marilda);
                    assertThat(evento.estabelecimentoId()).isEqualTo(tenant);
                });
    }

    // ------------------------------------------------------------------ as recusas

    @Test
    @DisplayName("quem está sendo atendido não faltou")
    void em_atendimento_nao_falta() throws SQLException {
        // Óbvio pelo motivo certo: a cliente está na cadeira.
        cenario();
        var criado = deOntem(13);
        forcarStatus(criado.id(), StatusAgendamento.EM_ATENDIMENTO);

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> registrarFalta.executar(criado.id(), criado.versao())));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-TRANSICAO_INVALIDA");
    }

    @Test
    @DisplayName("cancelado não vira falta")
    void cancelado_nao_vira_falta() {
        // Quem avisou que não vinha não faltou — são coisas diferentes, e misturá-las estragaria
        // justamente o número que sustenta a conversa com a cliente.
        cenario();
        var criado = deOntem(13);
        var cancelado = TenantContext.obter(tenant, () -> cancelar.executar(
                criado.id(), criado.versao(), "avisou que não vinha", true));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> registrarFalta.executar(criado.id(), cancelado.versao())));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-TRANSICAO_INVALIDA");
    }

    @Test
    @DisplayName("falta é terminal: não se marca duas vezes")
    void falta_e_terminal() {
        cenario();
        var criado = deOntem(13);
        var falta = TenantContext.obter(tenant,
                () -> registrarFalta.executar(criado.id(), criado.versao()));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> registrarFalta.executar(criado.id(), falta.versao())));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-TRANSICAO_INVALIDA");
    }

    @Test
    @DisplayName("a versão da tela precisa bater")
    void versao_desatualizada() {
        cenario();
        var criado = deOntem(13);

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> registrarFalta.executar(criado.id(), criado.versao() + 7)));

        assertThat(codigoDe(erro)).isEqualTo("ER-INF-VERSAO_DESATUALIZADA");
    }

    @Test
    @DisplayName("agendamento de outro salão não é encontrado")
    void isolamento_entre_saloes() {
        cenario();
        var doA = deOntem(13);
        UUID salaoB = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Outra",
                "outra-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        Throwable erro = catchThrowable(() -> TenantContext.executar(salaoB,
                () -> registrarFalta.executar(doA.id(), doA.versao())));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-AGENDAMENTO_NAO_ENCONTRADO");
    }

    // ------------------------------------------------------------------ auxiliares

    private List<String> statusDosBlocos(UUID agendamentoId) throws SQLException {
        try (var ps = comoOwner().prepareStatement(
                "select status from agendamento_bloco where agendamento_id = ? order by ordem")) {
            ps.setObject(1, agendamentoId);
            try (var rs = ps.executeQuery()) {
                var status = new java.util.ArrayList<String>();
                while (rs.next()) {
                    status.add(rs.getString(1));
                }
                return status;
            }
        }
    }

    private void forcarStatus(UUID agendamentoId, StatusAgendamento status) throws SQLException {
        try (var ps = comoOwner().prepareStatement(
                "update agendamento set status = ? where id = ?")) {
            ps.setString(1, status.name());
            ps.setObject(2, agendamentoId);
            ps.executeUpdate();
        }
    }
}
