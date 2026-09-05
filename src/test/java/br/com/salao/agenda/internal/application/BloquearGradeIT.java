package br.com.salao.agenda.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.agenda.api.BloqueioDaGrade;
import br.com.salao.agenda.api.DisponibilidadeApi;
import br.com.salao.agenda.api.HorarioDisponivel;
import br.com.salao.agenda.api.MotivoDoBloqueioDeAgenda;
import br.com.salao.agenda.api.OrigemDoAgendamento;
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

/** RT-AGD-007 — bloquear a grade. */
@WithMockUser(authorities = { Permissao.USUARIO_GERENCIAR, Permissao.CONFIG_GERENCIAR,
        Permissao.CLIENTE_ESCREVER, Permissao.AGENDA_LER_TODAS,
        Permissao.AGENDA_ESCREVER_TODAS })
class BloquearGradeIT extends AbstractPostgresIT {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate DIA = LocalDate.now(SAO_PAULO).plusDays(7);

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarProfissionalUseCase cadastrarProfissional;
    @Autowired private DefinirJornadaUseCase definirJornada;
    @Autowired private CadastrarServicoUseCase cadastrarServico;
    @Autowired private CadastrarRecursoUseCase cadastrarRecurso;
    @Autowired private CadastrarClienteUseCase cadastrarCliente;
    @Autowired private CriarAgendamentoUseCase criar;
    @Autowired private BloquearGradeUseCase bloquear;
    @Autowired private DisponibilidadeApi disponibilidade;

    private UUID tenant;
    private UUID ana;
    private UUID marilda;
    private UUID lavatorio;
    private UUID escova;

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
        lavatorio = TenantContext.obter(tenant,
                () -> cadastrarRecurso.executar("Lavatório", 1)).id();
        escova = TenantContext.obter(tenant, () -> cadastrarServico.executar("Escova", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 45)))).id();
    }

    private Instant as(int hora, int minuto) {
        return DIA.atTime(LocalTime.of(hora, minuto)).atZone(SAO_PAULO).toInstant();
    }

    private BloqueioDaGrade bloquearProfissional(int de, int ate) {
        return TenantContext.obter(tenant, () -> bloquear.executar(ana, null,
                MotivoDoBloqueioDeAgenda.PESSOAL, "consulta médica", as(de, 0), as(ate, 0)));
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ o banco garante

    @Test
    @DisplayName("marcar por cima de um bloqueio é recusado PELO BANCO")
    void agendamento_nao_passa_por_cima_do_bloqueio() {
        // É a razão de o bloqueio ser linha de `agendamento_bloco`. Uma tabela própria com
        // constraint espelhada teria duas exclusões para manter coerentes, e a segunda seria a
        // que ficasse para trás. Aqui a recusa vem da MESMA constraint dos atendimentos, sem uma
        // linha de código a mais em RT-AGD-002.
        cenario();
        bloquearProfissional(14, 16);

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> criar.executar(new NovoAgendamento(marilda, ana, escova, as(14, 30),
                        OrigemDoAgendamento.PAINEL, null, null, false, false, null, null))));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-CONFLITO_HORARIO");
    }

    @Test
    @DisplayName("o bloqueio some da disponibilidade")
    void bloqueio_some_da_disponibilidade() {
        cenario();
        bloquearProfissional(14, 16);

        var horarios = TenantContext.obter(tenant,
                () -> disponibilidade.consultar(DIA, escova, ana)).stream()
                .map(HorarioDisponivel::inicio).toList();

        assertThat(horarios).doesNotContain(as(14, 0), as(14, 30), as(15, 0), as(15, 30));
        assertThat(horarios).contains(as(13, 0), as(16, 0));
    }

    @Test
    @DisplayName("encostar no bloqueio é permitido")
    void encostar_no_bloqueio() {
        // [inicio, fim) vale aqui também (RN-AGD-010): um atendimento que termina às 14h em ponto
        // convive com um bloqueio que começa às 14h.
        cenario();
        bloquearProfissional(14, 16);

        assertThatCode(() -> TenantContext.executar(tenant,
                () -> criar.executar(new NovoAgendamento(marilda, ana, escova, as(13, 15),
                        OrigemDoAgendamento.PAINEL, null, null, false, false, null, null))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("bloquear por cima de um atendimento é recusado")
    void bloqueio_nao_passa_por_cima_do_agendamento() {
        // O comportamento certo: quem quer o horário livre cancela primeiro, avisando a cliente,
        // em vez de fazê-la chegar e descobrir.
        cenario();
        TenantContext.executar(tenant, () -> criar.executar(new NovoAgendamento(
                marilda, ana, escova, as(14, 0), OrigemDoAgendamento.PAINEL, null, null,
                false, false, null, null)));

        Throwable erro = catchThrowable(() -> bloquearProfissional(14, 16));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-CONFLITO_HORARIO");
        assertThat(erro).hasMessageContaining("cancele antes");
    }

    @Test
    @DisplayName("dois bloqueios do mesmo profissional não se sobrepõem")
    void bloqueios_nao_se_sobrepoem() {
        cenario();
        bloquearProfissional(14, 16);

        Throwable erro = catchThrowable(() -> bloquearProfissional(15, 17));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-CONFLITO_HORARIO");
    }

    // ------------------------------------------------------------------ recurso

    @Test
    @DisplayName("o lavatório quebrado impede a coloração, mesmo com a profissional livre")
    void bloqueio_de_recurso() {
        // O motivo que só existe nesta rotina, e a razão de ela não ser a mesma de RT-EQP-003:
        // `equipe` não sabe o que é um recurso.
        cenario();
        UUID mechas = TenantContext.obter(tenant, () -> cadastrarServico.executar("Mechas", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 30),
                        new EtapaBruta(TipoDeBloco.PAUSA, 40),
                        new EtapaBruta(TipoDeBloco.ATIVO, 30, Set.of(lavatorio))))).id();
        TenantContext.executar(tenant, () -> bloquear.executar(null, lavatorio,
                MotivoDoBloqueioDeAgenda.MANUTENCAO, "sifão entupido", as(9, 0), as(18, 0)));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> criar.executar(new NovoAgendamento(marilda, ana, mechas, as(13, 0),
                        OrigemDoAgendamento.PAINEL, null, null, false, false, null, null))));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-RECURSO_OCUPADO");
    }

    // ------------------------------------------------------------------ recusas de entrada

    @Test
    @DisplayName("bloqueio sem alvo é recusado")
    void bloqueio_precisa_de_alvo() {
        // Um bloqueio que não bloqueia ninguém nem nada é linha órfã com aparência de regra: some
        // da grade e ninguém entende por que o horário sumiu.
        cenario();

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> bloquear.executar(null, null, MotivoDoBloqueioDeAgenda.PESSOAL, null,
                        as(14, 0), as(16, 0))));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-BLOQUEIO_INVALIDO");
    }

    @Test
    @DisplayName("período invertido é 422, não 500")
    void periodo_invertido() {
        cenario();

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> bloquear.executar(ana, null, MotivoDoBloqueioDeAgenda.PESSOAL, null,
                        as(16, 0), as(14, 0))));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-BLOQUEIO_INVALIDO");
    }

    @Test
    @DisplayName("profissional de outro salão é recusado")
    void profissional_de_outro_salao() {
        // Não há FK para profissional — é de outro módulo — e por RN-CAT-011 ela também não
        // separaria os salões. Esta checagem é a única barreira.
        cenario();
        UUID salaoB = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Outra",
                "outra-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        UUID doB = TenantContext.obter(salaoB, () -> cadastrarProfissional.executar(
                new DadosDoProfissional("Júlia", null, "52998224725", TipoDeVinculo.EMPREGADO,
                        null, null, null, null, null, null))).id();

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> bloquear.executar(doB, null, MotivoDoBloqueioDeAgenda.PESSOAL, null,
                        as(14, 0), as(16, 0))));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-BLOQUEIO_INVALIDO");
    }

    // ------------------------------------------------------------------ remover

    @Test
    @DisplayName("remover o bloqueio devolve o horário no mesmo instante")
    void remover_devolve_o_horario() {
        // Apagar, e não desativar: um bloqueio não sustenta histórico nenhum. Mesma decisão de
        // RT-EQP-003 — apaga-se o que não sustenta nada.
        cenario();
        var bloqueio = bloquearProfissional(14, 16);

        TenantContext.executar(tenant, () -> bloquear.remover(bloqueio.id()));

        assertThatCode(() -> TenantContext.executar(tenant,
                () -> criar.executar(new NovoAgendamento(marilda, ana, escova, as(14, 30),
                        OrigemDoAgendamento.PAINEL, null, null, false, false, null, null))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("listar traz os bloqueios do período")
    void listar_no_periodo() {
        cenario();
        bloquearProfissional(14, 16);

        var doDia = TenantContext.obter(tenant, () -> bloquear.listar(as(9, 0), as(18, 0)));
        var deManha = TenantContext.obter(tenant, () -> bloquear.listar(as(9, 0), as(12, 0)));

        assertThat(doDia).singleElement().satisfies(b -> {
            assertThat(b.motivo()).isEqualTo(MotivoDoBloqueioDeAgenda.PESSOAL);
            assertThat(b.descricao()).isEqualTo("consulta médica");
            assertThat(b.profissionalId()).isEqualTo(ana);
        });
        assertThat(deManha).as("fora do período pedido").isEmpty();
    }

    @Test
    @DisplayName("um salão não vê o bloqueio do outro")
    void isolamento_entre_saloes() {
        cenario();
        bloquearProfissional(14, 16);
        UUID salaoB = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Outra",
                "outra-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        assertThat(TenantContext.obter(salaoB, () -> bloquear.listar(as(9, 0), as(18, 0))))
                .isEmpty();
    }
}
