package br.com.salao.agenda.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.agenda.api.AgendamentoCancelado;
import br.com.salao.agenda.api.AgendamentoResumo;
import br.com.salao.agenda.api.OrigemDoAgendamento;
import br.com.salao.agenda.api.StatusAgendamento;
import br.com.salao.catalogo.api.TipoDeBloco;
import br.com.salao.catalogo.internal.application.CadastrarRecursoUseCase;
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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/** RT-AGD-005 — cancelar, com motivo. */
@WithMockUser(authorities = { Permissao.USUARIO_GERENCIAR, Permissao.CONFIG_GERENCIAR,
        Permissao.CLIENTE_ESCREVER, Permissao.AGENDA_LER_TODAS,
        Permissao.AGENDA_ESCREVER_TODAS })
@RecordApplicationEvents
class CancelarAgendamentoIT extends AbstractPostgresIT {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate DIA = LocalDate.now(SAO_PAULO).plusDays(7);

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarProfissionalUseCase cadastrarProfissional;
    @Autowired private DefinirJornadaUseCase definirJornada;
    @Autowired private CadastrarServicoUseCase cadastrarServico;
    @Autowired private CadastrarRecursoUseCase cadastrarRecurso;
    @Autowired private CadastrarClienteUseCase cadastrarCliente;
    @Autowired private CriarAgendamentoUseCase criar;
    @Autowired private CancelarAgendamentoUseCase cancelar;
    @Autowired private ApplicationEvents eventos;

    private UUID tenant;
    private UUID ana;
    private UUID marilda;
    private UUID lavatorio;
    private UUID mechas;

    private void cenario() {
        tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        ana = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional("Ana", null, "11144477735", TipoDeVinculo.EMPREGADO,
                        null, null, null, null, null, null))).id();
        TenantContext.executar(tenant, () -> definirJornada.executar(ana,
                Map.of(DIA.getDayOfWeek(),
                        List.of(new PeriodoBruto(LocalTime.of(9, 0), LocalTime.of(18, 0))))));
        marilda = TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                new DadosDoCliente("Marilda", null, null, null, null)));
        // Um lavatório só: assim dá para ver se o cancelamento o devolve.
        lavatorio = TenantContext.obter(tenant,
                () -> cadastrarRecurso.executar("Lavatório", 1)).id();
        mechas = TenantContext.obter(tenant, () -> cadastrarServico.executar("Mechas", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 30),
                        new EtapaBruta(TipoDeBloco.PAUSA, 40),
                        new EtapaBruta(TipoDeBloco.ATIVO, 30, Set.of(lavatorio))))).id();
    }

    private Instant as(int hora) {
        return DIA.atTime(LocalTime.of(hora, 0)).atZone(SAO_PAULO).toInstant();
    }

    private AgendamentoResumo marcar(int hora) {
        return TenantContext.obter(tenant, () -> criar.executar(new NovoAgendamento(
                marilda, ana, mechas, as(hora), OrigemDoAgendamento.PAINEL, null, null,
                false, false, null, null)));
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ o horário volta

    @Test
    @DisplayName("cancelar libera TODOS os blocos para um novo agendamento")
    void cancelar_libera_todos_os_blocos_para_novo_agendamento() {
        // O teste que RN-AGD-006 nomeia. CANCELADO não está em OCUPAM_A_AGENDA — o mesmo conjunto
        // do filtro das exclusion constraints —, então mudar o status DOS BLOCOS, e não só o da
        // cabeça, é o que faz o horário ser oferecido de novo. Se os blocos ficassem para trás, o
        // cancelado continuaria bloqueando, sem erro, até a cliente ligar.
        cenario();
        var primeiro = marcar(13);

        TenantContext.executar(tenant, () -> cancelar.executar(primeiro.id(), primeiro.versao(),
                "cliente pediu para desmarcar", true));

        assertThatCode(() -> marcar(13))
                .as("o mesmo horário, com o mesmo lavatório").doesNotThrowAnyException();
    }

    @Test
    @DisplayName("o recurso volta junto, sem código específico")
    void cancelar_libera_o_recurso() {
        // O lavatório reservado para uma coloração cancelada fica livre no mesmo instante, pelo
        // mesmo mecanismo: o filtro de status das constraints vale para os dois.
        cenario();
        var primeiro = marcar(13);
        UUID julia = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional("Júlia", null, "52998224725", TipoDeVinculo.EMPREGADO,
                        null, null, null, null, null, null))).id();
        TenantContext.executar(tenant, () -> definirJornada.executar(julia,
                Map.of(DIA.getDayOfWeek(),
                        List.of(new PeriodoBruto(LocalTime.of(9, 0), LocalTime.of(18, 0))))));

        // Antes do cancelamento, a Júlia não consegue: o único lavatório está tomado.
        Throwable antes = catchThrowable(() -> TenantContext.executar(tenant,
                () -> criar.executar(new NovoAgendamento(marilda, julia, mechas, as(13),
                        OrigemDoAgendamento.PAINEL, null, null, false, false, null, null))));
        assertThat(codigoDe(antes)).isEqualTo("ER-AGD-RECURSO_OCUPADO");

        TenantContext.executar(tenant, () -> cancelar.executar(primeiro.id(), primeiro.versao(),
                "cliente desistiu", true));

        assertThatCode(() -> TenantContext.executar(tenant,
                () -> criar.executar(new NovoAgendamento(marilda, julia, mechas, as(13),
                        OrigemDoAgendamento.PAINEL, null, null, false, false, null, null))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("os blocos ficam com o status da cabeça")
    void blocos_acompanham() throws SQLException {
        cenario();
        var criado = marcar(13);

        TenantContext.executar(tenant,
                () -> cancelar.executar(criado.id(), criado.versao(), "cliente adoeceu", true));

        assertThat(statusDosBlocos(criado.id()))
                .containsExactly("CANCELADO", "CANCELADO", "CANCELADO");
    }

    // ------------------------------------------------------------------ o motivo

    @Test
    @DisplayName("cancelar sem motivo é recusado")
    void motivo_obrigatorio() {
        // Sem motivo, o histórico da cliente vira uma coluna de "cancelado" sem sentido.
        cenario();
        var criado = marcar(13);

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> cancelar.executar(criado.id(), criado.versao(), "   ", true)));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-MOTIVO_OBRIGATORIO");
        assertThat(TenantContext.obter(tenant, () -> criar.executar(new NovoAgendamento(
                marilda, ana, mechas, as(16), OrigemDoAgendamento.PAINEL, null, null,
                false, false, null, null)))).isNotNull();
    }

    @Test
    @DisplayName("o motivo e quem cancelou ficam na tabela, na mesma escrita do status")
    void motivo_e_autoria_gravados() throws SQLException {
        // "A Marilda desmarcou três vezes" e "eu remarquei a Marilda três vezes porque a Ana
        // faltou" são conversas diferentes. Sem a autoria, viram a mesma linha no relatório.
        cenario();
        var criado = marcar(13);

        TenantContext.executar(tenant, () -> cancelar.executar(criado.id(), criado.versao(),
                "Ana ficou doente", false));

        try (var ps = comoOwner().prepareStatement(
                "select motivo_cancelamento, cancelado_pela_cliente from agendamento where id = ?")) {
            ps.setObject(1, criado.id());
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("Ana ficou doente");
                assertThat(rs.getBoolean(2)).as("foi o salão, não a cliente").isFalse();
            }
        }
    }

    // ------------------------------------------------------------------ terminal é terminal

    @Test
    @DisplayName("cancelar um cancelado é recusado — e NÃO é como confirmar duas vezes")
    void cancelar_duas_vezes() {
        // Em RT-AGD-004, confirmar o já confirmado devolve o estado: o pedido era inócuo. Aqui o
        // segundo cancelamento chega com OUTRO motivo e outra autoria, e aceitá-lo em silêncio
        // sobrescreveria o registro do que aconteceu — ou o descartaria sem dizer.
        cenario();
        var criado = marcar(13);
        var cancelado = TenantContext.obter(tenant, () -> cancelar.executar(
                criado.id(), criado.versao(), "cliente desistiu", true));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> cancelar.executar(criado.id(), cancelado.versao(),
                        "na verdade a Ana faltou", false)));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-TRANSICAO_INVALIDA");
    }

    @Test
    @DisplayName("um atendimento em curso pode ser cancelado")
    void em_atendimento_cancela() throws SQLException {
        // A cliente levantou e foi embora no meio. RN-AGD-007 permite, e é o caso real.
        cenario();
        var criado = marcar(13);
        forcarStatus(criado.id(), StatusAgendamento.EM_ATENDIMENTO);

        var cancelado = TenantContext.obter(tenant, () -> cancelar.executar(
                criado.id(), criado.versao(), "cliente foi embora no meio", true));

        assertThat(cancelado.status()).isEqualTo(StatusAgendamento.CANCELADO);
    }

    @Test
    @DisplayName("concluído não se cancela")
    void concluido_nao_cancela() throws SQLException {
        cenario();
        var criado = marcar(13);
        forcarStatus(criado.id(), StatusAgendamento.CONCLUIDO);

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> cancelar.executar(criado.id(), criado.versao(), "engano", false)));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-TRANSICAO_INVALIDA");
    }

    @Test
    @DisplayName("a versão da tela precisa bater")
    void versao_desatualizada() {
        cenario();
        var criado = marcar(13);
        var outro = marcar(16);

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> cancelar.executar(criado.id(), criado.versao() + 7, "engano", false)));

        assertThat(codigoDe(erro)).isEqualTo("ER-INF-VERSAO_DESATUALIZADA");
        assertThat(outro).isNotNull();
    }

    // ------------------------------------------------------------------ o evento

    @Test
    @DisplayName("o evento leva a janela liberada, que é o que a fila de espera precisa")
    void evento_leva_a_janela_liberada() {
        // Um evento que só dissesse "cancelado" obrigaria o consumidor a buscar o agendamento de
        // volta — e ele acabou de deixar de existir para a agenda. Por isso também não há um
        // VagaLiberada separado: seriam dois eventos com o mesmo conteúdo no mesmo instante.
        cenario();
        var criado = marcar(13);

        TenantContext.executar(tenant, () -> cancelar.executar(criado.id(), criado.versao(),
                "cliente desistiu", true));

        assertThat(eventos.stream(AgendamentoCancelado.class).toList()).singleElement()
                .satisfies(evento -> {
                    assertThat(evento.inicio()).isEqualTo(as(13));
                    assertThat(evento.fim()).isEqualTo(as(13).plusSeconds(100 * 60));
                    assertThat(evento.profissionalId()).isEqualTo(ana);
                    assertThat(evento.servicoId()).isEqualTo(mechas);
                    assertThat(evento.canceladoPelaCliente()).isTrue();
                });
    }

    @Test
    @DisplayName("agendamento de outro salão não é encontrado")
    void isolamento_entre_saloes() {
        cenario();
        var doA = marcar(13);
        UUID salaoB = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Outra",
                "outra-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        Throwable erro = catchThrowable(() -> TenantContext.executar(salaoB,
                () -> cancelar.executar(doA.id(), doA.versao(), "engano", false)));

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
