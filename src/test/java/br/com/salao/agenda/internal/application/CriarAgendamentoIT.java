package br.com.salao.agenda.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.agenda.api.AgendamentoCriado;
import br.com.salao.agenda.api.AgendamentoResumo;
import br.com.salao.agenda.api.BlocoDoAgendamento;
import br.com.salao.agenda.api.OrigemDoAgendamento;
import br.com.salao.agenda.api.StatusAgendamento;
import br.com.salao.catalogo.api.TipoDeBloco;
import br.com.salao.catalogo.internal.application.CadastrarRecursoUseCase;
import br.com.salao.catalogo.internal.application.CadastrarServicoUseCase;
import br.com.salao.catalogo.internal.application.DefinirAtivacaoDoServicoUseCase;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-AGD-002 — criar agendamento. */
@WithMockUser(authorities = { Permissao.USUARIO_GERENCIAR, Permissao.CONFIG_GERENCIAR,
        Permissao.CLIENTE_ESCREVER, Permissao.AGENDA_LER_TODAS,
        Permissao.AGENDA_ESCREVER_TODAS })
@org.springframework.test.context.event.RecordApplicationEvents
class CriarAgendamentoIT extends AbstractPostgresIT {

    @Autowired private org.springframework.test.context.event.ApplicationEvents eventos;

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate DIA = LocalDate.now(SAO_PAULO).plusDays(7);
    private static final String[] CPFS = { "11144477735", "52998224725" };

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarProfissionalUseCase cadastrarProfissional;
    @Autowired private DefinirJornadaUseCase definirJornada;
    @Autowired private DefinirHabilidadesUseCase definirHabilidades;
    @Autowired private CadastrarServicoUseCase cadastrarServico;
    @Autowired private DefinirAtivacaoDoServicoUseCase definirAtivacaoDoServico;
    @Autowired private CadastrarRecursoUseCase cadastrarRecurso;
    @Autowired private CadastrarClienteUseCase cadastrarCliente;
    @Autowired private CriarAgendamentoUseCase criar;
    @Autowired private ConsultarAgendamentoUseCase consultar;

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

    private UUID corte(UUID tenant) {
        return TenantContext.obter(tenant, () -> cadastrarServico.executar("Corte", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 30)))).id();
    }

    /** Mechas: aplica 30, química age 40, lava e finaliza 30 — a lavagem exige lavatório. */
    private UUID mechas(UUID tenant, Set<UUID> lavagem) {
        return TenantContext.obter(tenant, () -> cadastrarServico.executar("Mechas", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 30),
                        new EtapaBruta(TipoDeBloco.PAUSA, 40),
                        new EtapaBruta(TipoDeBloco.ATIVO, 30, lavagem)))).id();
    }

    private Instant as(int hora, int minuto) {
        return DIA.atTime(LocalTime.of(hora, minuto)).atZone(SAO_PAULO).toInstant();
    }

    private NovoAgendamento pedido(UUID cliente, UUID profissional, UUID servico, Instant inicio) {
        return new NovoAgendamento(cliente, profissional, servico, inicio,
                OrigemDoAgendamento.PAINEL, null, null, false, false, null, null);
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ caminho feliz

    @Test
    @DisplayName("caminho feliz: cabeça e blocos gravados")
    void caminho_feliz_gera_cabeca_e_blocos() {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID marilda = cliente(tenant);
        UUID corte = corte(tenant);

        var criado = TenantContext.obter(tenant,
                () -> criar.executar(pedido(marilda, ana, corte, as(13, 0))));

        assertThat(criado.status()).as("marcado no balcão já nasce confirmado")
                .isEqualTo(StatusAgendamento.CONFIRMADO);
        assertThat(criado.inicio()).isEqualTo(as(13, 0));
        assertThat(criado.fim()).isEqualTo(as(13, 30));
        assertThat(criado.blocos()).singleElement()
                .satisfies(b -> assertThat(b.profissionalId()).isEqualTo(ana));
    }

    @Test
    @DisplayName("marcação pela cliente nasce SOLICITADO, não confirmada")
    void origem_decide_o_status() {
        // Sem essa distinção, a agenda de sábado encheria de horário que ninguém validou.
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID marilda = cliente(tenant);
        UUID corte = corte(tenant);

        var pelaWeb = TenantContext.obter(tenant, () -> criar.executar(new NovoAgendamento(
                marilda, ana, corte, as(13, 0), OrigemDoAgendamento.WEB, null, null,
                false, false, null, null)));

        assertThat(pelaWeb.status()).isEqualTo(StatusAgendamento.SOLICITADO);
    }

    @Test
    @DisplayName("o serviço com pausa libera o profissional e mantém o recurso")
    void servico_com_pausa_libera_profissional_e_mantem_recurso() {
        // É o modelo de blocos gravado: três linhas, e a do meio sem profissional. Se a pausa
        // tivesse profissional, o encaixe da próxima seria recusado pelo banco.
        UUID tenant = salao();
        UUID lavatorio = TenantContext.obter(tenant,
                () -> cadastrarRecurso.executar("Lavatório", 2)).id();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID marilda = cliente(tenant);
        UUID mechas = mechas(tenant, Set.of(lavatorio));

        var criado = TenantContext.obter(tenant,
                () -> criar.executar(pedido(marilda, ana, mechas, as(13, 0))));

        assertThat(criado.fim()).isEqualTo(as(14, 40));
        assertThat(criado.blocos()).hasSize(3);
        assertThat(criado.blocos()).extracting(BlocoDoAgendamento::profissionalId)
                .containsExactly(ana, null, ana);
        assertThat(criado.blocos()).extracting(BlocoDoAgendamento::recursoId)
                .as("só a lavagem usa o lavatório").containsExactly(null, null, lavatorio);
        assertThat(criado.blocos().get(2).unidade()).isEqualTo(1);
        assertThat(criado.minutosDoProfissional()).isEqualTo(60);
    }

    @Test
    @DisplayName("encaixe na pausa de outro agendamento é aceito")
    void encaixe_na_pausa_de_outro_agendamento_e_aceito() {
        // O teste que justifica o modelo inteiro. A química das mechas roda das 13h30 às 14h10 —
        // 40 minutos em que a Ana está livre. Um corte de 30 cabe ali, e o banco deixa porque o
        // bloco de pausa não tem profissional_id e não entra na exclusão.
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID marilda = cliente(tenant);
        UUID mechas = mechas(tenant, Set.of());
        UUID corte = corte(tenant);
        TenantContext.executar(tenant, () -> criar.executar(pedido(marilda, ana, mechas,
                as(13, 0))));

        var encaixe = TenantContext.obter(tenant,
                () -> criar.executar(pedido(marilda, ana, corte, as(13, 40))));

        assertThat(encaixe.inicio()).isEqualTo(as(13, 40));
        assertThat(encaixe.fim()).isEqualTo(as(14, 10));
    }

    @Test
    @DisplayName("dois blocos ATIVO sobrepostos são recusados pelo banco")
    void conflito_de_horario() {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID marilda = cliente(tenant);
        UUID mechas = mechas(tenant, Set.of());
        UUID corte = corte(tenant);
        TenantContext.executar(tenant, () -> criar.executar(pedido(marilda, ana, mechas,
                as(13, 0))));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> criar.executar(pedido(marilda, ana, corte, as(13, 10)))));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-CONFLITO_HORARIO");
    }

    // ------------------------------------------------------------------ as recusas

    @Test
    @DisplayName("profissional sem habilidade é rejeitado")
    void profissional_sem_habilidade_e_rejeitado() {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID marilda = cliente(tenant);
        UUID corte = corte(tenant);
        UUID mechas = mechas(tenant, Set.of());
        TenantContext.executar(tenant,
                () -> definirHabilidades.executar(ana, false, Set.of(corte), Map.of()));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> criar.executar(pedido(marilda, ana, mechas, as(13, 0)))));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-PROFISSIONAL_NAO_EXECUTA");
    }

    @Test
    @DisplayName("fora da jornada é rejeitado sem forçar")
    void fora_da_jornada_rejeitado() {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID marilda = cliente(tenant);
        UUID corte = corte(tenant);

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> criar.executar(pedido(marilda, ana, corte, as(18, 30)))));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-FORA_DA_JORNADA");
    }

    @Test
    @DisplayName("fora da jornada é aceito forçando, com justificativa na trilha")
    void fora_da_jornada_aceito_com_forcar_e_justificativa() throws SQLException {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID marilda = cliente(tenant);
        UUID corte = corte(tenant);

        var criado = TenantContext.obter(tenant, () -> criar.executar(new NovoAgendamento(
                marilda, ana, corte, as(18, 30), OrigemDoAgendamento.PAINEL, null, null,
                true, false, "cliente insistiu, Ana concordou em ficar", null)));

        assertThat(criado.inicio()).isEqualTo(as(18, 30));
        assertThat(trilhaDe("agendamento", "forcouForaJornada")).isEqualTo("true");
    }

    @Test
    @DisplayName("forçar sem justificativa é recusado")
    void forcar_exige_justificativa() {
        // Forçar sem dizer por quê é o mesmo que não ter regra.
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID marilda = cliente(tenant);
        UUID corte = corte(tenant);

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> criar.executar(new NovoAgendamento(marilda, ana, corte, as(18, 30),
                        OrigemDoAgendamento.PAINEL, null, null, true, false, "  ", null))));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-FORA_DA_JORNADA");
        assertThat(erro).hasMessageContaining("justificativa");
    }

    @Test
    @DisplayName("a PAUSA pode cair no almoço; o bloco ATIVO não")
    void pausa_pode_cair_fora_da_jornada() {
        // A cliente fica com a química agindo enquanto a profissional almoça — é o que acontece
        // no salão. Só os blocos que ocupam o profissional exigem jornada.
        UUID tenant = salao();
        UUID marilda = cliente(tenant);
        UUID mechas = mechas(tenant, Set.of());
        UUID ana = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional("Ana", null, CPFS[0], TipoDeVinculo.EMPREGADO,
                        null, null, null, null, null, null))).id();
        TenantContext.executar(tenant, () -> definirJornada.executar(ana,
                Map.of(DIA.getDayOfWeek(), List.of(
                        new PeriodoBruto(LocalTime.of(9, 0), LocalTime.of(13, 30)),
                        new PeriodoBruto(LocalTime.of(14, 10), LocalTime.of(18, 0))))));

        var criado = TenantContext.obter(tenant,
                () -> criar.executar(pedido(marilda, ana, mechas, as(13, 0))));

        assertThat(criado.fim()).as("aplica até 13h30, química no almoço, finaliza 14h10")
                .isEqualTo(as(14, 40));
    }

    @Test
    @DisplayName("passado é rejeitado, e aceito ao forçar")
    void passado_rejeitado_e_aceito_ao_forcar() {
        UUID tenant = salao();
        UUID marilda = cliente(tenant);
        UUID corte = corte(tenant);
        LocalDate ontem = LocalDate.now(SAO_PAULO).minusDays(1);
        UUID ana = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional("Ana", null, CPFS[0], TipoDeVinculo.EMPREGADO,
                        null, null, null, null, null, null))).id();
        TenantContext.executar(tenant, () -> definirJornada.executar(ana,
                Map.of(ontem.getDayOfWeek(),
                        List.of(new PeriodoBruto(LocalTime.of(9, 0), LocalTime.of(18, 0))))));
        Instant ontemAsDez = ontem.atTime(LocalTime.of(10, 0)).atZone(SAO_PAULO).toInstant();

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> criar.executar(pedido(marilda, ana, corte, ontemAsDez))));
        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-DATA_PASSADA");

        // A recepção lança o que ficou no papel — dizendo que é isso.
        var lancado = TenantContext.obter(tenant, () -> criar.executar(new NovoAgendamento(
                marilda, ana, corte, ontemAsDez, OrigemDoAgendamento.PAINEL, null, null,
                false, true, "atendimento de ontem que ficou no caderno", null)));
        assertThat(lancado.inicio()).isEqualTo(ontemAsDez);
    }

    @Test
    @DisplayName("serviço fora do cardápio é recusado")
    void servico_inativo() {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID marilda = cliente(tenant);
        UUID corte = corte(tenant);
        TenantContext.executar(tenant, () -> definirAtivacaoDoServico.executar(corte, false));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> criar.executar(pedido(marilda, ana, corte, as(13, 0)))));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-SERVICO_INATIVO");
    }

    @Test
    @DisplayName("cliente de outro salão é recusado")
    void cliente_de_outro_salao() {
        UUID salaoA = salao();
        UUID salaoB = salao();
        UUID ana = profissional(salaoA, "Ana", 0);
        UUID corte = corte(salaoA);
        UUID doB = cliente(salaoB);

        Throwable erro = catchThrowable(() -> TenantContext.executar(salaoA,
                () -> criar.executar(pedido(doB, ana, corte, as(13, 0)))));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-CLIENTE_NAO_ENCONTRADO");
    }

    // ------------------------------------------------------------------ recursos

    @Test
    @DisplayName("as duas unidades do lavatório são alocadas antes de faltar")
    void aloca_a_segunda_unidade() {
        UUID tenant = salao();
        UUID lavatorio = TenantContext.obter(tenant,
                () -> cadastrarRecurso.executar("Lavatório", 2)).id();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID julia = profissional(tenant, "Júlia", 1);
        UUID marilda = cliente(tenant);
        UUID mechas = mechas(tenant, Set.of(lavatorio));

        var daAna = TenantContext.obter(tenant,
                () -> criar.executar(pedido(marilda, ana, mechas, as(13, 0))));
        var daJulia = TenantContext.obter(tenant,
                () -> criar.executar(pedido(marilda, julia, mechas, as(13, 0))));

        assertThat(daAna.blocos().get(2).unidade()).isEqualTo(1);
        assertThat(daJulia.blocos().get(2).unidade()).as("a segunda unidade").isEqualTo(2);
    }

    @Test
    @DisplayName("recurso indisponível devolve 409")
    void recurso_indisponivel_retorna_409() {
        UUID tenant = salao();
        UUID lavatorio = TenantContext.obter(tenant,
                () -> cadastrarRecurso.executar("Lavatório", 1)).id();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID julia = profissional(tenant, "Júlia", 1);
        UUID marilda = cliente(tenant);
        UUID mechas = mechas(tenant, Set.of(lavatorio));
        TenantContext.executar(tenant, () -> criar.executar(pedido(marilda, ana, mechas,
                as(13, 0))));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> criar.executar(pedido(marilda, julia, mechas, as(13, 0)))));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-RECURSO_OCUPADO");
    }

    // ------------------------------------------------------------------ idempotência e escopo

    @Test
    @DisplayName("a mesma Idempotency-Key não duplica o agendamento")
    void idempotency_key_repetida_nao_duplica() throws SQLException {
        // Duplo clique no botão de marcar é real, e sem a chave ele cria dois agendamentos.
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID marilda = cliente(tenant);
        UUID corte = corte(tenant);
        String chave = UUID.randomUUID().toString();
        var comChave = new NovoAgendamento(marilda, ana, corte, as(13, 0),
                OrigemDoAgendamento.PAINEL, null, null, false, false, null, chave);

        var primeira = TenantContext.obter(tenant, () -> criar.executar(comChave));
        var segunda = TenantContext.obter(tenant, () -> criar.executar(comChave));

        assertThat(segunda.id()).isEqualTo(primeira.id());
        assertThat(quantosAgendamentos(tenant)).isEqualTo(1);
    }

    @Test
    @DisplayName("um salão não lê o agendamento do outro")
    void tenant_b_nao_ve_agendamento_do_tenant_a() {
        UUID salaoA = salao();
        UUID salaoB = salao();
        UUID ana = profissional(salaoA, "Ana", 0);
        UUID marilda = cliente(salaoA);
        UUID corte = corte(salaoA);
        var doA = TenantContext.obter(salaoA,
                () -> criar.executar(pedido(marilda, ana, corte, as(13, 0))));

        Throwable erro = catchThrowable(() -> TenantContext.executar(salaoB,
                () -> consultar.porId(doA.id())));

        // 404 e não 403: responder 403 confirmaria que aquele id existe em algum lugar.
        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-AGENDAMENTO_NAO_ENCONTRADO");
    }

    @Test
    @DisplayName("agenda:write:own NÃO cria: o vínculo usuário-profissional não existe")
    @WithMockUser(authorities = Permissao.AGENDA_ESCREVER_PROPRIA)
    void escopo_own_falha_fechado() {
        // Aceitar :own aqui seria tratá-lo como :all — um profissional marcando na agenda de
        // qualquer colega. `usuario` não tem profissional_id, então não há como filtrar. Falhar
        // fechado é a única saída honesta enquanto o vínculo não existir.
        assertThatThrownBy(() -> TenantContext.executar(UUID.randomUUID(),
                () -> criar.executar(pedido(UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), as(13, 0)))))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("o evento é publicado com id e sem nome de cliente")
    void evento_publicado_sem_dado_pessoal() {
        // AgendamentoCriado é o primeiro evento de domínio real do projeto. RN-INF-009: só
        // identificadores, nunca PII — o payload vive em `event_publication`, que é
        // infraestrutura e NÃO tem RLS, com os eventos de todos os salões na mesma tabela.
        //
        // Note que ele ainda NÃO gera linha em `event_publication`: o Spring Modulith grava uma
        // publicação por OUVINTE, e ninguém escuta este evento ainda — `notificacao` é Fase 1
        // adiante. Enquanto não houver ouvinte, o evento é publicado e não é durável. Está
        // registrado como pendência, e a durabilidade em si já é coberta por OutboxIT.
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID marilda = cliente(tenant);
        UUID corte = corte(tenant);

        var criado = TenantContext.obter(tenant,
                () -> criar.executar(pedido(marilda, ana, corte, as(13, 0))));

        var publicados = eventos.stream(AgendamentoCriado.class).toList();

        assertThat(publicados).singleElement().satisfies(evento -> {
            assertThat(evento.agendamentoId()).isEqualTo(criado.id());
            assertThat(evento.estabelecimentoId()).as("o tenant viaja no payload (RN-INF-009)")
                    .isEqualTo(tenant);
            assertThat(evento.clienteId()).isEqualTo(marilda);
            assertThat(evento.inicio()).isEqualTo(as(13, 0));
        });
        assertThat(publicados.getFirst().toString())
                .as("nenhum campo de texto onde caiba nome ou telefone")
                .doesNotContain("Marilda");
    }

    // ------------------------------------------------------------------ auxiliares

    private String trilhaDe(String entidade, String campo) throws SQLException {
        try (var ps = comoOwner().prepareStatement(
                "select depois->>? from auditoria where entidade = ?")) {
            ps.setString(1, campo);
            ps.setString(2, entidade);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private int quantosAgendamentos(UUID tenant) throws SQLException {
        try (var ps = comoOwner().prepareStatement(
                "select count(*) from agendamento where estabelecimento_id = ?")) {
            ps.setObject(1, tenant);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
