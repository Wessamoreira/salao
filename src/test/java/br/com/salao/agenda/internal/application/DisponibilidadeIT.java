package br.com.salao.agenda.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.agenda.api.DisponibilidadeApi;
import br.com.salao.agenda.api.HorarioDisponivel;
import br.com.salao.catalogo.api.TipoDeBloco;
import br.com.salao.catalogo.internal.application.CadastrarRecursoUseCase;
import br.com.salao.catalogo.internal.application.CadastrarServicoUseCase;
import br.com.salao.catalogo.internal.application.EtapaBruta;
import br.com.salao.equipe.api.MotivoDoBloqueio;
import br.com.salao.equipe.api.TipoDeVinculo;
import br.com.salao.equipe.internal.application.CadastrarProfissionalUseCase;
import br.com.salao.equipe.internal.application.DadosDoProfissional;
import br.com.salao.equipe.internal.application.DefinirHabilidadesUseCase;
import br.com.salao.equipe.internal.application.DefinirJornadaUseCase;
import br.com.salao.equipe.internal.application.PeriodoBruto;
import br.com.salao.equipe.internal.application.RegistrarBloqueioUseCase;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.sql.SQLException;
import java.time.DayOfWeek;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-AGD-001 — consultar disponibilidade. */
@WithMockUser(authorities = { Permissao.USUARIO_GERENCIAR, Permissao.CONFIG_GERENCIAR,
        Permissao.AGENDA_LER_TODAS })
class DisponibilidadeIT extends AbstractPostgresIT {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    /** Uma semana à frente: mesmo dia da semana, e longe do "agora" para RN-AGD-008 não interferir. */
    private static final LocalDate DIA = LocalDate.now(SAO_PAULO).plusDays(7);

    private static final String[] CPFS = { "11144477735", "52998224725", "87748248800" };

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarProfissionalUseCase cadastrarProfissional;
    @Autowired private DefinirJornadaUseCase definirJornada;
    @Autowired private DefinirHabilidadesUseCase definirHabilidades;
    @Autowired private RegistrarBloqueioUseCase registrarBloqueio;
    @Autowired private CadastrarServicoUseCase cadastrarServico;
    @Autowired private CadastrarRecursoUseCase cadastrarRecurso;
    @Autowired private DisponibilidadeApi disponibilidade;

    private UUID salao() {
        return provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
    }

    private UUID profissional(UUID tenant, String nome, int cpf, LocalTime abre, LocalTime fecha) {
        UUID id = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional(nome, null, CPFS[cpf], TipoDeVinculo.EMPREGADO,
                        null, null, null, null, null, null))).id();
        TenantContext.executar(tenant, () -> definirJornada.executar(id,
                Map.of(DIA.getDayOfWeek(), List.of(new PeriodoBruto(abre, fecha)))));
        return id;
    }

    private UUID escova(UUID tenant) {
        return TenantContext.obter(tenant, () -> cadastrarServico.executar("Escova", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 45)))).id();
    }

    private UUID coloracao(UUID tenant, Set<UUID> recursosDaLavagem) {
        return TenantContext.obter(tenant, () -> cadastrarServico.executar("Coloração", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 30),
                        new EtapaBruta(TipoDeBloco.PAUSA, 40),
                        new EtapaBruta(TipoDeBloco.ATIVO, 30, recursosDaLavagem)))).id();
    }

    private Instant as(LocalDate data, int hora, int minuto) {
        return data.atTime(LocalTime.of(hora, minuto)).atZone(SAO_PAULO).toInstant();
    }

    private List<Instant> horariosDe(UUID tenant, UUID servico, UUID profissional) {
        return TenantContext.obter(tenant,
                        () -> disponibilidade.consultar(DIA, servico, profissional)).stream()
                .map(HorarioDisponivel::inicio)
                .toList();
    }

    /**
     * Grava um agendamento direto no banco: RT-AGD-002 é quem vai criar de verdade, e a
     * disponibilidade precisa de algo ocupado para descontar. É fixture, não atalho.
     *
     * <p>{@code profissionalId} nulo significa "o bloco não ocupa profissional nenhum" — é a
     * pausa da química, quando só o recurso está tomado. O <em>cabeçalho</em> continua tendo dono:
     * agendamento sem profissional não existe, e o banco não deixa. Nesse caso é outra pessoa
     * qualquer, porque quem atende a outra cliente não interessa a este teste.
     */
    private void ocupar(UUID tenant, UUID profissionalId, UUID recursoId, Integer unidade,
                        Instant inicio, Instant fim, String status) throws SQLException {
        UUID donoDoAgendamento = profissionalId == null ? UUID.randomUUID() : profissionalId;
        UUID agendamentoId;
        try (var ps = comoOwner().prepareStatement("""
                insert into agendamento (estabelecimento_id, cliente_id, profissional_id,
                                         servico_id, status, inicio, fim, origem)
                values (?, gen_random_uuid(), ?, gen_random_uuid(), ?, ?, ?, 'PAINEL')
                returning id
                """)) {
            ps.setObject(1, tenant);
            ps.setObject(2, donoDoAgendamento);
            ps.setString(3, status);
            ps.setObject(4, inicio.atZone(SAO_PAULO).toOffsetDateTime());
            ps.setObject(5, fim.atZone(SAO_PAULO).toOffsetDateTime());
            try (var rs = ps.executeQuery()) {
                rs.next();
                agendamentoId = rs.getObject(1, UUID.class);
            }
        }
        try (var ps = comoOwner().prepareStatement("""
                insert into agendamento_bloco (estabelecimento_id, agendamento_id, ordem, tipo,
                                               profissional_id, recurso_id, unidade, periodo,
                                               status)
                values (?, ?, 0, 'ATIVO', ?, ?, ?, tstzrange(?, ?), ?)
                """)) {
            ps.setObject(1, tenant);
            ps.setObject(2, agendamentoId);
            ps.setObject(3, profissionalId);
            ps.setObject(4, recursoId);
            ps.setObject(5, unidade);
            ps.setObject(6, inicio.atZone(SAO_PAULO).toOffsetDateTime());
            ps.setObject(7, fim.atZone(SAO_PAULO).toOffsetDateTime());
            ps.setString(8, status);
            ps.executeUpdate();
        }
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ a conta básica

    @Test
    @DisplayName("a jornada vira horários na grade do salão")
    void jornada_vira_horarios() {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0, LocalTime.of(9, 0), LocalTime.of(11, 0));
        UUID escova = escova(tenant);

        var horarios = horariosDe(tenant, escova, ana);

        assertThat(horarios).containsExactly(
                as(DIA, 9, 0), as(DIA, 9, 15), as(DIA, 9, 30), as(DIA, 9, 45),
                as(DIA, 10, 0), as(DIA, 10, 15));
    }

    @Test
    @DisplayName("fora da jornada não aparece como livre")
    void fora_da_jornada_nao_aparece_como_livre() {
        // RN-AGD-002. Agendar às 19h para quem sai às 18h é conflito garantido.
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0, LocalTime.of(9, 0), LocalTime.of(11, 0));
        UUID escova = escova(tenant);

        assertThat(horariosDe(tenant, escova, ana))
                .doesNotContain(as(DIA, 8, 45), as(DIA, 10, 30), as(DIA, 11, 0), as(DIA, 19, 0));
    }

    @Test
    @DisplayName("sem jornada no dia, nenhum horário")
    void sem_jornada_no_dia() {
        UUID tenant = salao();
        UUID ana = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional("Ana", null, CPFS[0], TipoDeVinculo.EMPREGADO,
                        null, null, null, null, null, null))).id();
        UUID escova = escova(tenant);

        assertThat(horariosDe(tenant, escova, ana)).isEmpty();
    }

    @Test
    @DisplayName("bloqueio some da disponibilidade")
    void bloqueio_some() {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0, LocalTime.of(9, 0), LocalTime.of(13, 0));
        UUID escova = escova(tenant);
        TenantContext.executar(tenant, () -> registrarBloqueio.executar(ana,
                MotivoDoBloqueio.CURSO, null, DIA, DIA, LocalTime.of(10, 0),
                LocalTime.of(12, 0)));

        var horarios = horariosDe(tenant, escova, ana);

        assertThat(horarios).contains(as(DIA, 9, 0), as(DIA, 9, 15));
        assertThat(horarios).as("o curso das 10h às 12h").doesNotContain(
                as(DIA, 9, 30), as(DIA, 10, 0), as(DIA, 11, 0));
        assertThat(horarios).contains(as(DIA, 12, 0), as(DIA, 12, 15));
    }

    @Test
    @DisplayName("horário já marcado some")
    void horario_ocupado_some() throws SQLException {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0, LocalTime.of(9, 0), LocalTime.of(12, 0));
        UUID escova = escova(tenant);
        ocupar(tenant, ana, null, null, as(DIA, 10, 0), as(DIA, 11, 0), "CONFIRMADO");

        var horarios = horariosDe(tenant, escova, ana);

        assertThat(horarios).doesNotContain(as(DIA, 9, 30), as(DIA, 10, 0), as(DIA, 10, 30));
        assertThat(horarios).as("encostado no fim do que já existe").contains(as(DIA, 11, 0));
    }

    @Test
    @DisplayName("agendamento cancelado devolve o horário")
    void cancelado_devolve_o_horario() throws SQLException {
        // O filtro de status é o mesmo das constraints de exclusão, e precisa continuar sendo: se
        // divergissem, a aplicação esconderia horário que o banco aceitaria.
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0, LocalTime.of(9, 0), LocalTime.of(12, 0));
        UUID escova = escova(tenant);
        ocupar(tenant, ana, null, null, as(DIA, 10, 0), as(DIA, 11, 0), "CANCELADO");

        assertThat(horariosDe(tenant, escova, ana)).contains(as(DIA, 10, 0));
    }

    // ------------------------------------------------------------------ a pausa

    @Test
    @DisplayName("a PAUSA de uma coloração deixa o profissional livre para um encaixe")
    void pausa_libera_encaixe() throws SQLException {
        // É a rotina inteira se pagando. A coloração de outra cliente ocupa a Ana das 9h às 9h30 e
        // das 10h10 às 10h40 — no meio, a química age e ela está livre. Uma escova de 45 minutos
        // não cabe nesses 40, mas um corte de 30 cabe.
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0, LocalTime.of(9, 0), LocalTime.of(18, 0));
        UUID corte = TenantContext.obter(tenant, () -> cadastrarServico.executar("Corte", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 30)))).id();

        ocupar(tenant, ana, null, null, as(DIA, 9, 0), as(DIA, 9, 30), "CONFIRMADO");
        ocupar(tenant, ana, null, null, as(DIA, 10, 10), as(DIA, 10, 40), "CONFIRMADO");

        var horarios = horariosDe(tenant, corte, ana);

        assertThat(horarios).as("os 40 minutos da química viram um corte vendido")
                .contains(as(DIA, 9, 30));
        assertThat(horarios).doesNotContain(as(DIA, 9, 45), as(DIA, 10, 0), as(DIA, 10, 15));
    }

    // ------------------------------------------------------------------ recursos

    @Test
    @DisplayName("lavatório esgotado tira o horário, mesmo com o profissional livre")
    void recurso_esgotado() throws SQLException {
        UUID tenant = salao();
        UUID lavatorio = TenantContext.obter(tenant,
                () -> cadastrarRecurso.executar("Lavatório", 1)).id();
        UUID ana = profissional(tenant, "Ana", 0, LocalTime.of(9, 0), LocalTime.of(13, 0));
        UUID coloracao = coloracao(tenant, Set.of(lavatorio));

        // O único lavatório está tomado das 10h às 12h por outra pessoa.
        ocupar(tenant, null, lavatorio, 1, as(DIA, 10, 0), as(DIA, 12, 0), "CONFIRMADO");

        var horarios = horariosDe(tenant, coloracao, ana);

        // A lavagem é o TERCEIRO bloco: cai 70 minutos depois do início, não no início. Começar
        // às 9h põe a lavagem às 10h10, em cima do lavatório ocupado — e é justamente esse
        // deslocamento que uma agenda de janela única não saberia calcular.
        assertThat(horarios).as("todo início cuja lavagem caia entre 10h e 12h")
                .doesNotContain(as(DIA, 9, 0), as(DIA, 9, 15), as(DIA, 10, 0), as(DIA, 10, 30));
        assertThat(horarios).as("às 11h a lavagem é 12h10, com o lavatório livre de novo")
                .containsExactly(as(DIA, 11, 0), as(DIA, 11, 15));
    }

    @Test
    @DisplayName("dois lavatórios aguentam duas lavagens simultâneas")
    void recurso_com_duas_unidades() throws SQLException {
        UUID tenant = salao();
        UUID lavatorio = TenantContext.obter(tenant,
                () -> cadastrarRecurso.executar("Lavatório", 2)).id();
        UUID ana = profissional(tenant, "Ana", 0, LocalTime.of(9, 0), LocalTime.of(13, 0));
        UUID coloracao = coloracao(tenant, Set.of(lavatorio));
        ocupar(tenant, null, lavatorio, 1, as(DIA, 10, 0), as(DIA, 12, 0), "CONFIRMADO");

        assertThat(horariosDe(tenant, coloracao, ana))
                .as("sobra o segundo lavatório").contains(as(DIA, 9, 15), as(DIA, 10, 0));
    }

    // ------------------------------------------------------------------ quem atende

    @Test
    @DisplayName("sem profissional informado, traz todos os que fazem o serviço")
    void qualquer_profissional() {
        UUID tenant = salao();
        // Jornada de 45 minutos exatos: uma escova cabe, e só uma. Assim a lista tem um horário
        // por pessoa, e o que se afirma é quem apareceu — não quantas vezes.
        UUID ana = profissional(tenant, "Ana", 0, LocalTime.of(9, 0), LocalTime.of(9, 45));
        UUID julia = profissional(tenant, "Júlia", 1, LocalTime.of(9, 0), LocalTime.of(9, 45));
        UUID escova = escova(tenant);

        var todos = TenantContext.obter(tenant,
                () -> disponibilidade.consultar(DIA, escova, null));

        assertThat(todos).extracting(HorarioDisponivel::profissionalId)
                .containsExactlyInAnyOrder(ana, julia);
        assertThat(todos).extracting(HorarioDisponivel::inicio)
                .containsOnly(as(DIA, 9, 0));
    }

    @Test
    @DisplayName("quem não executa o serviço não aparece — e perguntar por ela é 422")
    void profissional_que_nao_executa() {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0, LocalTime.of(9, 0), LocalTime.of(18, 0));
        UUID julia = profissional(tenant, "Júlia", 1, LocalTime.of(9, 0), LocalTime.of(18, 0));
        UUID escova = escova(tenant);
        UUID corte = TenantContext.obter(tenant, () -> cadastrarServico.executar("Corte", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 30)))).id();
        TenantContext.executar(tenant,
                () -> definirHabilidades.executar(julia, false, Set.of(corte), Map.of()));

        var quemFazEscova = TenantContext.obter(tenant,
                () -> disponibilidade.consultar(DIA, escova, null));

        assertThat(quemFazEscova).extracting(HorarioDisponivel::profissionalId).containsOnly(ana);

        // Lista vazia faria a recepção oferecer outro dia, quando o problema é que a Júlia não faz
        // escova. RN-AGD-001 com nome.
        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> disponibilidade.consultar(DIA, escova, julia)));
        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-PROFISSIONAL_NAO_EXECUTA");
    }

    @Test
    @DisplayName("a duração própria do profissional muda os horários")
    void duracao_propria_muda_os_horarios() {
        // RT-CAT-004 chegando na agenda: a Ana faz escova em 30 minutos, então cabe mais uma na
        // mesma jornada.
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0, LocalTime.of(9, 0), LocalTime.of(10, 0));
        UUID escova = escova(tenant);
        TenantContext.executar(tenant, () -> definirHabilidades.executar(
                ana, true, Set.of(), Map.of(escova, 30)));

        assertThat(horariosDe(tenant, escova, ana))
                .as("com 45 minutos só caberia às 9h").containsExactly(
                        as(DIA, 9, 0), as(DIA, 9, 15), as(DIA, 9, 30));
    }

    @Test
    @DisplayName("serviço inexistente é 404")
    void servico_inexistente() {
        UUID tenant = salao();

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> disponibilidade.consultar(DIA, UUID.randomUUID(), null)));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-SERVICO_NAO_ENCONTRADO");
    }

    @Test
    @DisplayName("horário no passado não é oferecido")
    void passado_nao_aparece() {
        // RN-AGD-008 já vale na oferta: prometer 9h quando são 15h faria a recepção oferecer um
        // horário que o cadastro vai recusar.
        UUID tenant = salao();
        LocalDate ontem = LocalDate.now(SAO_PAULO).minusDays(1);
        UUID ana = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional("Ana", null, CPFS[0], TipoDeVinculo.EMPREGADO,
                        null, null, null, null, null, null))).id();
        TenantContext.executar(tenant, () -> definirJornada.executar(ana,
                Map.of(ontem.getDayOfWeek(),
                        List.of(new PeriodoBruto(LocalTime.of(9, 0), LocalTime.of(18, 0))))));
        UUID escova = escova(tenant);

        assertThat(TenantContext.obter(tenant,
                () -> disponibilidade.consultar(ontem, escova, ana))).isEmpty();
    }

    @Test
    @DisplayName("um salão não vê a disponibilidade do outro")
    void isolamento_entre_saloes() {
        UUID salaoA = salao();
        UUID salaoB = salao();
        UUID ana = profissional(salaoA, "Ana", 0, LocalTime.of(9, 0), LocalTime.of(18, 0));
        UUID escovaDoA = escova(salaoA);

        Throwable erro = catchThrowable(() -> TenantContext.executar(salaoB,
                () -> disponibilidade.consultar(DIA, escovaDoA, ana)));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-SERVICO_NAO_ENCONTRADO");
    }

    @Test
    @DisplayName("consultar disponibilidade exige permissão de agenda")
    @WithMockUser(authorities = Permissao.CLIENTE_LER)
    void sem_permissao() {
        assertThatThrownBy(() -> TenantContext.executar(UUID.randomUUID(),
                () -> disponibilidade.consultar(DIA, UUID.randomUUID(), null)))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ------------------------------------------------------------------ o banco garante

    @Test
    @DisplayName("o banco recusa dois blocos do mesmo profissional no mesmo horário")
    void banco_recusa_sobreposicao_de_profissional() throws SQLException {
        // RN-AGD-004. A aplicação valida por educação; o banco garante — trava de aplicação não
        // resiste a duas instâncias concorrentes.
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0, LocalTime.of(9, 0), LocalTime.of(18, 0));
        ocupar(tenant, ana, null, null, as(DIA, 10, 0), as(DIA, 11, 0), "CONFIRMADO");

        assertThatThrownBy(() -> ocupar(tenant, ana, null, null,
                as(DIA, 10, 30), as(DIA, 11, 30), "SOLICITADO"))
                .hasMessageContaining("bloco_sem_sobreposicao_profissional");
    }

    @Test
    @DisplayName("o banco deixa encostar, e é assim que a agenda fica cheia")
    void banco_deixa_encostar() throws SQLException {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0, LocalTime.of(9, 0), LocalTime.of(18, 0));
        ocupar(tenant, ana, null, null, as(DIA, 10, 0), as(DIA, 11, 0), "CONFIRMADO");

        assertThatCode(() -> ocupar(tenant, ana, null, null,
                as(DIA, 11, 0), as(DIA, 12, 0), "CONFIRMADO")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a UNIDADE é o que faz o banco garantir a capacidade do recurso")
    void banco_garante_capacidade_por_unidade() throws SQLException {
        // Sem a unidade na constraint, EXCLUDE garantiria "no máximo um lavatório ocupado" — e a
        // capacidade de três dependeria de uma contagem da aplicação, sob concorrência, sem trava.
        UUID tenant = salao();
        UUID lavatorio = TenantContext.obter(tenant,
                () -> cadastrarRecurso.executar("Lavatório", 2)).id();
        ocupar(tenant, null, lavatorio, 1, as(DIA, 10, 0), as(DIA, 11, 0), "CONFIRMADO");

        assertThatCode(() -> ocupar(tenant, null, lavatorio, 2,
                as(DIA, 10, 0), as(DIA, 11, 0), "CONFIRMADO"))
                .as("a segunda unidade").doesNotThrowAnyException();
        assertThatThrownBy(() -> ocupar(tenant, null, lavatorio, 1,
                as(DIA, 10, 30), as(DIA, 11, 30), "CONFIRMADO"))
                .as("a primeira, de novo").hasMessageContaining("bloco_sem_sobreposicao_recurso");
    }
}
