package br.com.salao.agenda.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.agenda.api.AgendaEmpurrada;
import br.com.salao.agenda.api.AgendamentoResumo;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/** RT-AGD-008 — empurrar a agenda. */
@WithMockUser(authorities = { Permissao.USUARIO_GERENCIAR, Permissao.CONFIG_GERENCIAR,
        Permissao.CLIENTE_ESCREVER, Permissao.AGENDA_LER_TODAS,
        Permissao.AGENDA_ESCREVER_TODAS })
@RecordApplicationEvents
class EmpurrarAgendaIT extends AbstractPostgresIT {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate DIA = LocalDate.now(SAO_PAULO).plusDays(7);

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarProfissionalUseCase cadastrarProfissional;
    @Autowired private DefinirJornadaUseCase definirJornada;
    @Autowired private CadastrarServicoUseCase cadastrarServico;
    @Autowired private CadastrarClienteUseCase cadastrarCliente;
    @Autowired private CriarAgendamentoUseCase criar;
    @Autowired private EmpurrarAgendaUseCase empurrar;
    @Autowired private BloquearGradeUseCase bloquear;
    @Autowired private ConsultarAgendamentoUseCase consultar;
    @Autowired private ApplicationEvents eventos;

    private UUID tenant;
    private UUID ana;
    private UUID marilda;
    private UUID corte;

    private void cenario() {
        tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        ana = profissional("Ana", "11144477735");
        marilda = TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                new DadosDoCliente("Marilda", null, null, null, null)));
        corte = TenantContext.obter(tenant, () -> cadastrarServico.executar("Corte", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 30)))).id();
    }

    private UUID profissional(String nome, String cpf) {
        UUID id = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional(nome, null, cpf, TipoDeVinculo.EMPREGADO,
                        null, null, null, null, null, null))).id();
        TenantContext.executar(tenant, () -> definirJornada.executar(id,
                Map.of(DIA.getDayOfWeek(),
                        List.of(new PeriodoBruto(LocalTime.of(9, 0), LocalTime.of(18, 0))))));
        return id;
    }

    private Instant as(int hora, int minuto) {
        return DIA.atTime(LocalTime.of(hora, minuto)).atZone(SAO_PAULO).toInstant();
    }

    private AgendamentoResumo marcar(UUID profissionalId, int hora, int minuto) {
        return TenantContext.obter(tenant, () -> criar.executar(new NovoAgendamento(
                marilda, profissionalId, corte, as(hora, minuto), OrigemDoAgendamento.PAINEL,
                null, null, false, false, null, null)));
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ o caso da rotina

    @Test
    @DisplayName("a Ana atrasou meia hora: o dia dela inteiro anda junto")
    void empurra_o_dia_inteiro() {
        cenario();
        var nove = marcar(ana, 9, 0);
        var dez = marcar(ana, 10, 0);
        var onze = marcar(ana, 11, 0);

        var movidos = TenantContext.obter(tenant, () -> empurrar.executar(
                ana, as(9, 0), 30, "Ana chegou 30 minutos atrasada"));

        assertThat(movidos).hasSize(3);
        assertThat(movidos).extracting(AgendamentoResumo::inicio)
                .containsExactly(as(9, 30), as(10, 30), as(11, 30));
        assertThat(movidos).extracting(AgendamentoResumo::id)
                .containsExactly(nove.id(), dez.id(), onze.id());
    }

    @Test
    @DisplayName("empurrar para frente não faz o primeiro colidir com o segundo")
    void empurrar_nao_colide_consigo_mesmo() {
        // Movê-los um a um exigiria ir do último para o primeiro — detalhe que se acerta na
        // implementação e se perde na primeira manutenção. Apagando os blocos de TODOS antes de
        // gravar qualquer um, a ordem deixa de importar.
        cenario();
        marcar(ana, 9, 0);
        marcar(ana, 9, 30);
        marcar(ana, 10, 0);

        var movidos = TenantContext.obter(tenant, () -> empurrar.executar(
                ana, as(9, 0), 30, "atraso"));

        assertThat(movidos).extracting(AgendamentoResumo::inicio)
                .as("colados, e empurrados sem colidir entre si")
                .containsExactly(as(9, 30), as(10, 0), as(10, 30));
    }

    @Test
    @DisplayName("só o que começa a partir do horário pedido se move")
    void so_a_partir_do_horario() {
        cenario();
        var antes = marcar(ana, 9, 0);
        var depois = marcar(ana, 14, 0);

        TenantContext.executar(tenant, () -> empurrar.executar(
                ana, as(13, 0), 30, "atrasou depois do almoço"));

        assertThat(TenantContext.obter(tenant, () -> consultar.porId(antes.id())).inicio())
                .as("o da manhã ficou onde estava").isEqualTo(as(9, 0));
        assertThat(TenantContext.obter(tenant, () -> consultar.porId(depois.id())).inicio())
                .isEqualTo(as(14, 30));
    }

    @Test
    @DisplayName("a agenda de outra profissional não se mexe")
    void so_a_agenda_pedida() {
        cenario();
        UUID julia = profissional("Júlia", "52998224725");
        var daJulia = marcar(julia, 9, 0);
        marcar(ana, 10, 0);

        TenantContext.executar(tenant, () -> empurrar.executar(ana, as(9, 0), 30, "atraso"));

        assertThat(TenantContext.obter(tenant, () -> consultar.porId(daJulia.id())).inicio())
                .isEqualTo(as(9, 0));
    }

    @Test
    @DisplayName("puxar para trás também funciona")
    void empurrao_negativo() {
        // A profissional chegou adiantada, ou o atraso foi menor do que se pensava.
        cenario();
        marcar(ana, 10, 0);
        marcar(ana, 11, 0);

        var movidos = TenantContext.obter(tenant, () -> empurrar.executar(
                ana, as(10, 0), -15, "o atraso foi menor do que parecia"));

        assertThat(movidos).extracting(AgendamentoResumo::inicio)
                .containsExactly(as(9, 45), as(10, 45));
    }

    // ------------------------------------------------------------------ tudo ou nada

    @Test
    @DisplayName("se um não cabe, NADA se move")
    void tudo_ou_nada() {
        // Uma agenda empurrada pela metade é pior do que uma não empurrada: a recepção não
        // consegue dizer em que estado ela está, e já ligou para parte das clientes.
        cenario();
        var nove = marcar(ana, 9, 0);
        var dez = marcar(ana, 10, 0);
        // Um bloqueio às 10h30 impede o segundo de andar meia hora.
        TenantContext.executar(tenant, () -> bloquear.executar(ana, null,
                MotivoDoBloqueioDeAgenda.PESSOAL, "compromisso", as(10, 30), as(11, 0)));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> empurrar.executar(ana, as(9, 0), 30, "atraso")));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-EMPURRAO_NAO_COUBE");
        assertThat(TenantContext.obter(tenant, () -> consultar.porId(nove.id())).inicio())
                .as("o primeiro NÃO se moveu").isEqualTo(as(9, 0));
        assertThat(TenantContext.obter(tenant, () -> consultar.porId(dez.id())).inicio())
                .isEqualTo(as(10, 0));
    }

    @Test
    @DisplayName("o erro nomeia o atendimento que barrou")
    void erro_nomeia_o_culpado() {
        // É o que a recepção precisa para decidir: cancela aquele, fala com a cliente, ou empurra
        // menos.
        cenario();
        marcar(ana, 9, 0);
        marcar(ana, 15, 0);
        TenantContext.executar(tenant, () -> bloquear.executar(ana, null,
                MotivoDoBloqueioDeAgenda.PESSOAL, "compromisso", as(15, 30), as(16, 0)));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> empurrar.executar(ana, as(9, 0), 30, "atraso")));

        assertThat(erro).hasMessageContaining(as(15, 0).toString());
    }

    // ------------------------------------------------------------------ jornada e status

    @Test
    @DisplayName("sair da jornada é a consequência esperada, não um erro")
    void sai_da_jornada_sem_reclamar() {
        // Se a Ana atrasou meia hora, o dia dela termina meia hora depois. Recusar porque o último
        // passa das 18h tornaria a rotina inútil no caso em que ela mais serve.
        cenario();
        marcar(ana, 17, 30);

        var movidos = TenantContext.obter(tenant, () -> empurrar.executar(
                ana, as(17, 0), 30, "atraso"));

        assertThat(movidos).singleElement()
                .satisfies(a -> assertThat(a.fim()).as("termina às 18h30, depois do expediente")
                        .isEqualTo(as(18, 30)));
    }

    @Test
    @DisplayName("o que está EM ATENDIMENTO não se move")
    void em_atendimento_fica() throws SQLException {
        // Empurrar quem já está na cadeira mudaria o horário de quem está sendo atendida.
        cenario();
        var emCurso = marcar(ana, 9, 0);
        var proximo = marcar(ana, 11, 0);
        forcarStatus(emCurso.id(), StatusAgendamento.EM_ATENDIMENTO);

        var movidos = TenantContext.obter(tenant, () -> empurrar.executar(
                ana, as(9, 0), 30, "atraso"));

        assertThat(movidos).extracting(AgendamentoResumo::id).containsExactly(proximo.id());
        assertThat(TenantContext.obter(tenant, () -> consultar.porId(emCurso.id())).inicio())
                .isEqualTo(as(9, 0));
    }

    @Test
    @DisplayName("cancelado não anda junto")
    void cancelado_nao_anda() throws SQLException {
        cenario();
        var cancelado = marcar(ana, 9, 0);
        marcar(ana, 11, 0);
        forcarStatus(cancelado.id(), StatusAgendamento.CANCELADO);

        var movidos = TenantContext.obter(tenant, () -> empurrar.executar(
                ana, as(9, 0), 30, "atraso"));

        assertThat(movidos).hasSize(1);
    }

    @Test
    @DisplayName("agenda vazia devolve lista vazia, sem erro")
    void agenda_vazia() {
        cenario();

        assertThat(TenantContext.obter(tenant,
                () -> empurrar.executar(ana, as(9, 0), 30, "atraso"))).isEmpty();
    }

    // ------------------------------------------------------------------ recusas de entrada

    @Test
    @DisplayName("empurrar sem justificativa é recusado")
    void justificativa_obrigatoria() {
        // O empurrão força a jornada de todo mundo que toca. Forçar sem dizer por quê é o mesmo
        // que não ter regra.
        cenario();
        marcar(ana, 9, 0);

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> empurrar.executar(ana, as(9, 0), 30, "  ")));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-EMPURRAO_NAO_COUBE");
        assertThat(erro).hasMessageContaining("justificativa");
    }

    @Test
    @DisplayName("deslocamento maior que um dia é erro de digitação, não atraso")
    void limite_de_deslocamento() {
        cenario();
        marcar(ana, 9, 0);

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> empurrar.executar(ana, as(9, 0), 3000, "atraso")));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-EMPURRAO_NAO_COUBE");
        assertThat(erro).hasMessageContaining("um dia");
    }

    @Test
    @DisplayName("empurrar zero não move nada")
    void zero_nao_move() {
        cenario();
        marcar(ana, 9, 0);

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> empurrar.executar(ana, as(9, 0), 0, "atraso")));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-EMPURRAO_NAO_COUBE");
    }

    // ------------------------------------------------------------------ rastro

    @Test
    @DisplayName("um evento para o empurrão inteiro, não um por atendimento")
    void um_evento_para_o_conjunto() {
        // Seis eventos separados viram seis mensagens dizendo "seu horário mudou" sem contexto. Um
        // evento de empurrão permite ao consumidor saber que a causa foi uma só.
        cenario();
        marcar(ana, 9, 0);
        marcar(ana, 10, 0);

        TenantContext.executar(tenant, () -> empurrar.executar(ana, as(9, 0), 30, "atraso"));

        assertThat(eventos.stream(AgendaEmpurrada.class).toList()).singleElement()
                .satisfies(evento -> {
                    assertThat(evento.agendamentos()).hasSize(2);
                    assertThat(evento.minutos()).isEqualTo(30);
                    assertThat(evento.profissionalId()).isEqualTo(ana);
                    assertThat(evento.estabelecimentoId()).isEqualTo(tenant);
                });
    }

    @Test
    @DisplayName("a trilha registra que a jornada foi forçada")
    void trilha_diz_que_forcou() throws SQLException {
        cenario();
        marcar(ana, 9, 0);

        TenantContext.executar(tenant, () -> empurrar.executar(ana, as(9, 0), 30, "Ana atrasou"));

        try (var st = comoOwner().createStatement();
             var rs = st.executeQuery("select depois->>'justificativa', "
                     + "depois->>'forcouForaJornada' from auditoria "
                     + "where acao = 'AGENDA_EMPURRADA'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("Ana atrasou");
            assertThat(rs.getString(2)).isEqualTo("true");
        }
    }

    @Test
    @DisplayName("a agenda de outro salão não é alcançada")
    void isolamento_entre_saloes() {
        cenario();
        marcar(ana, 9, 0);
        UUID salaoB = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Outra",
                "outra-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        Throwable erro = catchThrowable(() -> TenantContext.executar(salaoB,
                () -> empurrar.executar(ana, as(9, 0), 30, "atraso")));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-PROFISSIONAL_NAO_EXECUTA");
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
