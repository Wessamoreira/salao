package br.com.salao.agenda.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.agenda.api.AgendamentoReagendado;
import br.com.salao.agenda.api.AgendamentoResumo;
import br.com.salao.agenda.api.BlocoDoAgendamento;
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
import br.com.salao.equipe.internal.application.DefinirHabilidadesUseCase;
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

/** RT-AGD-003 — reagendar. */
@WithMockUser(authorities = { Permissao.USUARIO_GERENCIAR, Permissao.CONFIG_GERENCIAR,
        Permissao.CLIENTE_ESCREVER, Permissao.AGENDA_LER_TODAS,
        Permissao.AGENDA_ESCREVER_TODAS })
@RecordApplicationEvents
class ReagendarIT extends AbstractPostgresIT {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate DIA = LocalDate.now(SAO_PAULO).plusDays(7);
    private static final String[] CPFS = { "11144477735", "52998224725" };

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarProfissionalUseCase cadastrarProfissional;
    @Autowired private DefinirJornadaUseCase definirJornada;
    @Autowired private DefinirHabilidadesUseCase definirHabilidades;
    @Autowired private CadastrarServicoUseCase cadastrarServico;
    @Autowired private CadastrarRecursoUseCase cadastrarRecurso;
    @Autowired private CadastrarClienteUseCase cadastrarCliente;
    @Autowired private CriarAgendamentoUseCase criar;
    @Autowired private ReagendarUseCase reagendar;
    @Autowired private ApplicationEvents eventos;

    private UUID salao() {
        return provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
    }

    private UUID profissional(UUID tenant, String nome, int cpf) {
        UUID id = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional(nome, null, CPFS[cpf], TipoDeVinculo.EMPREGADO,
                        null, null, null, null, null, null))).id();
        TenantContext.executar(tenant, () -> definirJornada.executar(id,
                Map.of(DIA.getDayOfWeek(),
                        List.of(new PeriodoBruto(LocalTime.of(9, 0), LocalTime.of(18, 0))))));
        return id;
    }

    private UUID cliente(UUID tenant) {
        return TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                new DadosDoCliente("Marilda", null, null, null, null)));
    }

    private UUID escova(UUID tenant) {
        return TenantContext.obter(tenant, () -> cadastrarServico.executar("Escova", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 45)))).id();
    }

    private UUID mechas(UUID tenant, Set<UUID> lavagem) {
        return TenantContext.obter(tenant, () -> cadastrarServico.executar("Mechas", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 30),
                        new EtapaBruta(TipoDeBloco.PAUSA, 40),
                        new EtapaBruta(TipoDeBloco.ATIVO, 30, lavagem)))).id();
    }

    private Instant as(int hora, int minuto) {
        return DIA.atTime(LocalTime.of(hora, minuto)).atZone(SAO_PAULO).toInstant();
    }

    private AgendamentoResumo criarAs(UUID tenant, UUID cliente, UUID profissional, UUID servico, Instant hora) {
        return TenantContext.obter(tenant, () -> criar.executar(new NovoAgendamento(
                cliente, profissional, servico, hora, OrigemDoAgendamento.PAINEL, null, null,
                false, false, null, null)));
    }

    private PedidoDeReagendamento mover(UUID id, Instant para, int versao) {
        return new PedidoDeReagendamento(id, para, null, null, versao, false, false, null);
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ o caso que engana

    @Test
    @DisplayName("mover 15 minutos para frente NÃO conflita com o próprio agendamento")
    void mover_para_horario_sobreposto() {
        // O defeito mais fácil de escrever nesta rotina. Das 13h para as 13h15 é mover para um
        // horário que se sobrepõe ao atual: sem apagar os blocos antigos ANTES de gravar os
        // novos, na mesma transação, o agendamento colide consigo mesmo e a recepção vê "horário
        // ocupado" apontando para o atendimento que ela está tentando mover.
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID marilda = cliente(tenant);
        UUID escova = escova(tenant);
        var criado = criarAs(tenant, marilda, ana, escova, as(13, 0));

        var movido = TenantContext.obter(tenant,
                () -> reagendar.executar(mover(criado.id(), as(13, 15), criado.versao())));

        assertThat(movido.inicio()).isEqualTo(as(13, 15));
        assertThat(movido.fim()).isEqualTo(as(14, 0));
    }

    @Test
    @DisplayName("o recurso do próprio agendamento é liberado antes da realocação")
    void recurso_proprio_nao_disputa_consigo_mesmo() {
        // Mesma armadilha, na outra dimensão: com um único lavatório, mover as mechas 15 minutos
        // faria o agendamento disputar o lavatório com ele mesmo.
        UUID tenant = salao();
        UUID lavatorio = TenantContext.obter(tenant,
                () -> cadastrarRecurso.executar("Lavatório", 1)).id();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID marilda = cliente(tenant);
        UUID mechas = mechas(tenant, Set.of(lavatorio));
        var criado = criarAs(tenant, marilda, ana, mechas, as(13, 0));

        var movido = TenantContext.obter(tenant,
                () -> reagendar.executar(mover(criado.id(), as(13, 15), criado.versao())));

        assertThat(movido.inicio()).isEqualTo(as(13, 15));
        assertThat(movido.blocos()).extracting(BlocoDoAgendamento::recursoId)
                .containsExactly(null, null, lavatorio);
    }

    // ------------------------------------------------------------------ mudar de coluna

    @Test
    @DisplayName("arrastar para outra coluna adota o ritmo de quem vai atender")
    void mudar_de_profissional_recalcula_a_duracao() {
        // A Ana faz escova em 30 minutos; a Júlia, nos 45 do catálogo. Arrastar da coluna da Ana
        // para a da Júlia faz o bloco CRESCER — é o que RN-CAT-013 diz, e é o que a grade precisa
        // mostrar. Esconder seria prometer um horário que não existe.
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID julia = profissional(tenant, "Júlia", 1);
        UUID marilda = cliente(tenant);
        UUID escova = escova(tenant);
        TenantContext.executar(tenant, () -> definirHabilidades.executar(
                ana, true, Set.of(), Map.of(escova, 30)));
        var criado = criarAs(tenant, marilda, ana, escova, as(13, 0));
        assertThat(criado.fim()).as("com a Ana, 30 minutos").isEqualTo(as(13, 30));

        var movido = TenantContext.obter(tenant, () -> reagendar.executar(
                new PedidoDeReagendamento(criado.id(), as(13, 0), julia, null,
                        criado.versao(), false, false, null)));

        assertThat(movido.profissionalId()).isEqualTo(julia);
        assertThat(movido.fim()).as("com a Júlia, os 45 do catálogo").isEqualTo(as(13, 45));
    }

    @Test
    @DisplayName("arrastar na MESMA coluna preserva a duração, inclusive uma forçada")
    void mesma_coluna_preserva_a_duracao() {
        // Quem move um bloco de 90 minutos quinze minutos para frente espera um bloco de 90
        // minutos. Sem esta regra, a duração forçada na criação se perderia no primeiro arrasto.
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID marilda = cliente(tenant);
        UUID mechas = mechas(tenant, Set.of());
        var criado = TenantContext.obter(tenant, () -> criar.executar(new NovoAgendamento(
                marilda, ana, mechas, as(13, 0), OrigemDoAgendamento.PAINEL, null, 90,
                false, false, null, null)));
        assertThat(criado.fim()).isEqualTo(as(14, 30));

        var movido = TenantContext.obter(tenant,
                () -> reagendar.executar(mover(criado.id(), as(15, 0), criado.versao())));

        assertThat(movido.fim()).as("continua 90 minutos").isEqualTo(as(16, 30));
    }

    @Test
    @DisplayName("arrastar para quem não faz o serviço é rejeitado")
    void outra_coluna_sem_habilidade() {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID julia = profissional(tenant, "Júlia", 1);
        UUID marilda = cliente(tenant);
        UUID escova = escova(tenant);
        UUID corte = TenantContext.obter(tenant, () -> cadastrarServico.executar("Corte", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 30)))).id();
        TenantContext.executar(tenant,
                () -> definirHabilidades.executar(julia, false, Set.of(corte), Map.of()));
        var criado = criarAs(tenant, marilda, ana, escova, as(13, 0));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> reagendar.executar(new PedidoDeReagendamento(criado.id(), as(13, 0), julia,
                        null, criado.versao(), false, false, null))));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-PROFISSIONAL_NAO_EXECUTA");
    }

    // ------------------------------------------------------------------ as recusas

    @Test
    @DisplayName("mover para cima de outro agendamento é conflito")
    void conflito_com_outro() {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID marilda = cliente(tenant);
        UUID escova = escova(tenant);
        var primeiro = criarAs(tenant, marilda, ana, escova, as(13, 0));
        criarAs(tenant, marilda, ana, escova, as(15, 0));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> reagendar.executar(mover(primeiro.id(), as(15, 15), primeiro.versao()))));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-CONFLITO_HORARIO");
    }

    @Test
    @DisplayName("o conflito não deixa o agendamento sem blocos")
    void conflito_faz_rollback_completo() {
        // Se a transação falhar depois do delete, o horário não pode ficar vago: a cliente
        // continua marcada onde estava.
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID marilda = cliente(tenant);
        UUID escova = escova(tenant);
        var primeiro = criarAs(tenant, marilda, ana, escova, as(13, 0));
        criarAs(tenant, marilda, ana, escova, as(15, 0));

        catchThrowable(() -> TenantContext.executar(tenant,
                () -> reagendar.executar(mover(primeiro.id(), as(15, 15), primeiro.versao()))));

        var depois = TenantContext.obter(tenant, () -> criar.executar(new NovoAgendamento(
                marilda, ana, escova, as(11, 0), OrigemDoAgendamento.PAINEL, null, null,
                false, false, null, null)));
        assertThat(depois).isNotNull();
        assertThat(TenantContext.obter(tenant, () -> consultarBlocos(primeiro.id())))
                .as("o agendamento original continua reservado às 13h").isEqualTo(1);
    }

    @Test
    @DisplayName("versão desatualizada é recusada: duas recepcionistas no mesmo bloco")
    void versao_desatualizada() {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID marilda = cliente(tenant);
        UUID escova = escova(tenant);
        var criado = criarAs(tenant, marilda, ana, escova, as(13, 0));
        TenantContext.executar(tenant,
                () -> reagendar.executar(mover(criado.id(), as(14, 0), criado.versao())));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> reagendar.executar(mover(criado.id(), as(16, 0), criado.versao()))));

        assertThat(codigoDe(erro)).isEqualTo("ER-INF-VERSAO_DESATUALIZADA");
    }

    @Test
    @DisplayName("fora da jornada é recusado, e aceito ao forçar com justificativa")
    void fora_da_jornada() {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID marilda = cliente(tenant);
        UUID escova = escova(tenant);
        var criado = criarAs(tenant, marilda, ana, escova, as(13, 0));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> reagendar.executar(mover(criado.id(), as(19, 0), criado.versao()))));
        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-FORA_DA_JORNADA");

        var forcado = TenantContext.obter(tenant, () -> reagendar.executar(
                new PedidoDeReagendamento(criado.id(), as(19, 0), null, null, criado.versao(),
                        true, false, "cliente só consegue depois do expediente")));
        assertThat(forcado.inicio()).isEqualTo(as(19, 0));
    }

    @Test
    @DisplayName("atendimento em curso não se arrasta")
    void em_atendimento_nao_move() throws SQLException {
        // A cliente já está na cadeira. E terminal, muito menos: mover o passado reescreveria o
        // que aconteceu.
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID marilda = cliente(tenant);
        UUID escova = escova(tenant);
        var criado = criarAs(tenant, marilda, ana, escova, as(13, 0));
        forcarStatus(criado.id(), StatusAgendamento.EM_ATENDIMENTO);

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> reagendar.executar(mover(criado.id(), as(14, 0), criado.versao()))));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-NAO_PODE_REAGENDAR");
    }

    @Test
    @DisplayName("agendamento de outro salão não é encontrado")
    void isolamento_entre_saloes() {
        UUID salaoA = salao();
        UUID salaoB = salao();
        UUID ana = profissional(salaoA, "Ana", 0);
        UUID marilda = cliente(salaoA);
        UUID escova = escova(salaoA);
        var doA = criarAs(salaoA, marilda, ana, escova, as(13, 0));

        Throwable erro = catchThrowable(() -> TenantContext.executar(salaoB,
                () -> reagendar.executar(mover(doA.id(), as(14, 0), doA.versao()))));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-AGENDAMENTO_NAO_ENCONTRADO");
    }

    // ------------------------------------------------------------------ rastro

    @Test
    @DisplayName("o evento leva o DE e o PARA")
    void evento_leva_de_e_para() {
        // Quem consome precisa dos dois: "seu horário mudou de 13h para 15h". Um evento que só
        // dissesse o destino obrigaria o consumidor a ter guardado a origem.
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID marilda = cliente(tenant);
        UUID escova = escova(tenant);
        var criado = criarAs(tenant, marilda, ana, escova, as(13, 0));

        TenantContext.executar(tenant,
                () -> reagendar.executar(mover(criado.id(), as(15, 0), criado.versao())));

        assertThat(eventos.stream(AgendamentoReagendado.class).toList()).singleElement()
                .satisfies(evento -> {
                    assertThat(evento.inicioAnterior()).isEqualTo(as(13, 0));
                    assertThat(evento.inicioNovo()).isEqualTo(as(15, 0));
                    assertThat(evento.mudouDeProfissional()).isFalse();
                    assertThat(evento.estabelecimentoId()).isEqualTo(tenant);
                });
    }

    @Test
    @DisplayName("a trilha guarda de onde saiu e para onde foi")
    void trilha_guarda_o_movimento() throws SQLException {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID marilda = cliente(tenant);
        UUID escova = escova(tenant);
        var criado = criarAs(tenant, marilda, ana, escova, as(13, 0));

        TenantContext.executar(tenant,
                () -> reagendar.executar(mover(criado.id(), as(15, 0), criado.versao())));

        try (var st = comoOwner().createStatement();
             var rs = st.executeQuery("select antes->>'inicio', depois->>'inicio' from auditoria "
                     + "where acao = 'AGENDAMENTO_REAGENDADO'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo(as(13, 0).toString());
            assertThat(rs.getString(2)).isEqualTo(as(15, 0).toString());
        }
    }

    // ------------------------------------------------------------------ auxiliares

    private void forcarStatus(UUID agendamentoId, StatusAgendamento status) throws SQLException {
        try (var ps = comoOwner().prepareStatement(
                "update agendamento set status = ? where id = ?")) {
            ps.setString(1, status.name());
            ps.setObject(2, agendamentoId);
            ps.executeUpdate();
        }
    }

    private int consultarBlocos(UUID agendamentoId) {
        try (var ps = comoOwner().prepareStatement(
                "select count(*) from agendamento_bloco where agendamento_id = ?")) {
            ps.setObject(1, agendamentoId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
