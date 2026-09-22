package br.com.salao.agenda.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

import br.com.salao.agenda.api.AgendamentoResumo;
import br.com.salao.agenda.api.EsperaNaFila;
import br.com.salao.agenda.api.Frequencia;
import br.com.salao.agenda.api.OrigemDoAgendamento;
import br.com.salao.agenda.api.SerieCriada;
import br.com.salao.agenda.internal.domain.ChamadaDaFila;
import br.com.salao.agenda.internal.domain.OrigemDaVaga;
import br.com.salao.agenda.internal.domain.ResultadoDaChamada;
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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * RT-AGD-010 — a vaga que abre, quem cabe nela, e o que a recepção registra.
 *
 * <p>Com Postgres de verdade, porque o que se prova aqui é a costura: o cancelamento gravando a
 * vaga, a disponibilidade de RT-AGD-001 dizendo quem cabe, e a aceitação passando pela exclusion
 * constraint de RT-AGD-002.
 */
@WithMockUser(authorities = { Permissao.USUARIO_GERENCIAR, Permissao.CONFIG_GERENCIAR,
        Permissao.CLIENTE_ESCREVER, Permissao.AGENDA_LER_TODAS,
        Permissao.AGENDA_ESCREVER_TODAS })
class VagaAbertaIT extends AbstractPostgresIT {

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
    @Autowired private ReagendarUseCase reagendar;
    @Autowired private CriarRecorrenciaUseCase criarSerie;
    @Autowired private EncerrarRecorrenciaUseCase encerrarSerie;
    @Autowired private DefinirHabilidadesUseCase definirHabilidades;
    @Autowired private FilaDeEsperaUseCase fila;
    @Autowired private ConsultarVagasAbertasUseCase vagasAbertas;
    @Autowired private RegistrarChamadaDaFilaUseCase registrar;
    @Autowired private EnfileirarDiaDaSerieUseCase enfileirarDia;

    private UUID tenant;
    private UUID ana;
    private UUID julia;
    private UUID marilda;
    private UUID joana;
    private UUID bia;
    private UUID carla;
    private UUID coloracao;
    private UUID corte;
    private UUID escova;
    private UUID lavagem;
    private UUID hidratacao;

    private void cenario() {
        tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        ana = profissional("Ana", "11144477735");
        julia = profissional("Júlia", "52998224725");
        marilda = cliente("Marilda", "11987654321");
        joana = cliente("Joana", null);
        bia = cliente("Bia", null);
        carla = cliente("Carla", null);
        // Um lavatório só: assim dá para tomá-lo com a Júlia e ver a vaga da Ana deixar de servir.
        UUID lavatorio = TenantContext.obter(tenant,
                () -> cadastrarRecurso.executar("Lavatório", 1)).id();
        coloracao = servico("Coloração", 120, Set.of());
        corte = servico("Corte", 30, Set.of());
        escova = servico("Escova", 45, Set.of());
        lavagem = servico("Lavagem", 30, Set.of(lavatorio));
        hidratacao = servico("Hidratação", 60, Set.of(lavatorio));
    }

    private UUID profissional(String nome, String cpf) {
        UUID id = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional(nome, null, cpf, TipoDeVinculo.EMPREGADO,
                        null, null, null, null, null, null))).id();
        // A jornada vale para o dia da semana: toda semana, e é isso que a série usa.
        TenantContext.executar(tenant, () -> definirJornada.executar(id,
                Map.of(DIA.getDayOfWeek(),
                        List.of(new PeriodoBruto(LocalTime.of(9, 0), LocalTime.of(18, 0))))));
        return id;
    }

    private UUID cliente(String nome, String telefone) {
        return TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                new DadosDoCliente(nome, null, telefone, null, null)));
    }

    private UUID servico(String nome, int minutos, Set<UUID> recursos) {
        return TenantContext.obter(tenant, () -> cadastrarServico.executar(nome, null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, minutos, recursos)))).id();
    }

    private static Instant as(int hora, int minuto) {
        return em(DIA, hora, minuto);
    }

    private static Instant em(LocalDate dia, int hora, int minuto) {
        return dia.atTime(LocalTime.of(hora, minuto)).atZone(SAO_PAULO).toInstant();
    }

    private AgendamentoResumo marcar(UUID cliente, UUID profissional, UUID servico, Instant quando) {
        return TenantContext.obter(tenant, () -> criar.executar(new NovoAgendamento(cliente,
                profissional, servico, quando, OrigemDoAgendamento.PAINEL, null, null, false,
                false, null, null)));
    }

    private void desmarcar(AgendamentoResumo agendamento) {
        TenantContext.executar(tenant, () -> cancelar.executar(agendamento.id(),
                agendamento.versao(), "cliente pediu para desmarcar", true));
    }

    /** A coloração das 14h às 16h da Ana, cancelada: a vaga de quase todos os testes. */
    private void coloracaoDaCarlaCancelada() {
        desmarcar(marcar(carla, ana, coloracao, as(14, 0)));
    }

    private EsperaNaFila esperar(UUID cliente, UUID servico, UUID profissional) {
        return esperar(cliente, servico, profissional, DIA, DIA, null, null);
    }

    private EsperaNaFila esperar(UUID cliente, UUID servico, UUID profissional, LocalDate de,
                                 LocalDate ate, LocalTime horaDe, LocalTime horaAte) {
        return TenantContext.obter(tenant, () -> fila.entrar(cliente, servico, profissional, de,
                ate, horaDe, horaAte, null));
    }

    private List<VagaComCandidatas> vagas() {
        return TenantContext.obter(tenant, () -> vagasAbertas.executar());
    }

    private VagaComCandidatas aUnicaVaga() {
        List<VagaComCandidatas> vagas = vagas();
        assertThat(vagas).hasSize(1);
        return vagas.get(0);
    }

    private ChamadaDaFila chamar(UUID vaga, EsperaNaFila espera,
                                            ResultadoDaChamada resultado, Instant inicio,
                                            String motivo) {
        return TenantContext.obter(tenant, () -> registrar.executar(vaga, espera.id(), resultado,
                inicio, motivo));
    }

    private Throwable erroAo(Runnable acao) {
        return catchThrowable(acao::run);
    }

    private static String codigoDe(Throwable e) {
        assertThat(e).isInstanceOf(ErroDeDominio.class);
        return ((ErroDeDominio) e).codigo().codigo();
    }

    private long contar(String tabela) throws SQLException {
        try (var conexao = comoOwner();
             var ps = conexao.prepareStatement(
                     "select count(*) from " + tabela + " where estabelecimento_id = ?")) {
            ps.setObject(1, tenant);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private List<UUID> naFila() {
        return TenantContext.obter(tenant, () -> fila.naFila()).stream()
                .map(linha -> linha.espera().clienteId()).toList();
    }

    // ------------------------------------------------------------------ a vaga nasce

    @Test
    @DisplayName("cancelar abre a vaga, e quem espera outro serviço que caiba é candidata")
    void cancelar_abre_vaga() {
        // RN-AGD-009 e RN-AGD-025 juntas: a coloração de duas horas cancelada serve para quem
        // espera um corte — o caso que o casamento por serviço desperdiçava.
        cenario();
        esperar(marilda, corte, null);

        coloracaoDaCarlaCancelada();

        var vaga = aUnicaVaga();
        assertThat(vaga.profissionalId()).isEqualTo(ana);
        assertThat(vaga.profissional()).isEqualTo("Ana");
        assertThat(vaga.inicio()).isEqualTo(as(14, 0));
        assertThat(vaga.fim()).isEqualTo(as(16, 0));
        assertThat(vaga.origem()).isEqualTo(OrigemDaVaga.CANCELAMENTO);
        assertThat(vaga.candidatas()).singleElement().satisfies(c -> {
            assertThat(c.cliente()).isEqualTo("Marilda");
            assertThat(c.telefone()).as("a recepção liga").isNotBlank();
            assertThat(c.servico()).isEqualTo("Corte");
            assertThat(c.posicao()).isEqualTo(1);
            assertThat(c.horarios()).as("de 15 em 15 minutos, começando dentro da vaga")
                    .first().isEqualTo(as(14, 0));
            assertThat(c.horarios()).last().isEqualTo(as(15, 45));
            assertThat(c.chamadas()).isEmpty();
        });
    }

    @Test
    @DisplayName("reagendar libera o horário antigo tanto quanto cancelar")
    void reagendar_abre_vaga() {
        cenario();
        esperar(marilda, corte, null);
        var coloracaoDaCarla = marcar(carla, ana, coloracao, as(14, 0));

        TenantContext.executar(tenant, () -> reagendar.executar(new PedidoDeReagendamento(
                coloracaoDaCarla.id(), as(9, 0), null, null, coloracaoDaCarla.versao(), false,
                false, null)));

        var vaga = aUnicaVaga();
        assertThat(vaga.origem()).isEqualTo(OrigemDaVaga.REAGENDAMENTO);
        assertThat(vaga.inicio()).isEqualTo(as(14, 0));
        assertThat(vaga.fim()).isEqualTo(as(16, 0));
    }

    @Test
    @DisplayName("reagendar sem liberar nada não abre vaga")
    void reagendar_sem_liberar_nao_abre_vaga() throws SQLException {
        cenario();
        var coloracaoDaCarla = marcar(carla, ana, coloracao, as(14, 0));

        TenantContext.executar(tenant, () -> reagendar.executar(new PedidoDeReagendamento(
                coloracaoDaCarla.id(), as(14, 0), null, null, coloracaoDaCarla.versao(), false,
                false, null)));

        assertThat(contar("vaga_aberta")).isZero();
    }

    @Test
    @DisplayName("cancelar um horário que já passou não abre vaga")
    void passado_nao_abre_vaga() throws SQLException {
        // Marcado no passado à força, com justificativa (RN-AGD-008): cancelar libera um tempo que
        // ninguém mais pode usar, e gravar a vaga seria uma linha sem propósito.
        cenario();
        LocalDate ontem = LocalDate.now(SAO_PAULO).minusDays(1);
        var noPassado = TenantContext.obter(tenant, () -> criar.executar(new NovoAgendamento(carla,
                ana, corte, em(ontem, 3, 0), OrigemDoAgendamento.PAINEL, null, null, true, true,
                "lançamento atrasado do atendimento", null)));

        desmarcar(noPassado);

        assertThat(contar("vaga_aberta")).isZero();
    }

    @Test
    @DisplayName("cancelamento recusado não abre vaga")
    void cancelamento_recusado_nao_abre_vaga() throws SQLException {
        cenario();
        var coloracaoDaCarla = marcar(carla, ana, coloracao, as(14, 0));

        Throwable erro = erroAo(() -> TenantContext.executar(tenant, () -> cancelar.executar(
                coloracaoDaCarla.id(), coloracaoDaCarla.versao() + 1, "desmarcou", true)));

        assertThat(codigoDe(erro)).isEqualTo("ER-INF-VERSAO_DESATUALIZADA");
        assertThat(contar("vaga_aberta")).isZero();
    }

    @Test
    @DisplayName("vaga dentro de vaga aberta não duplica")
    void vaga_dentro_de_vaga_nao_duplica() throws SQLException {
        // A fila preenche a vaga e a nova cliente também cancela: o tempo já está anunciado, e
        // uma segunda vaga listaria as mesmas pessoas duas vezes.
        cenario();
        esperar(marilda, corte, null);
        coloracaoDaCarlaCancelada();

        desmarcar(marcar(joana, ana, corte, as(14, 0)));

        assertThat(contar("vaga_aberta")).isEqualTo(1);
        assertThat(vagas()).hasSize(1);
    }

    // ------------------------------------------------------------------ quem cabe

    @Test
    @DisplayName("só cabe o que a disponibilidade aceita, não o que a conta de minutos diz")
    void so_cabe_o_que_a_disponibilidade_aceita() {
        // Escova das 14h às 14h45 cancelada, com um corte da Joana às 14h45. Coloração de duas
        // horas não cabe; corte cabe às 14h e às 14h15 — às 14h30 esbarraria no da Joana.
        cenario();
        var escovaDaCarla = marcar(carla, ana, escova, as(14, 0));
        marcar(joana, ana, corte, as(14, 45));
        esperar(marilda, coloracao, null);
        esperar(bia, corte, null);

        desmarcar(escovaDaCarla);

        assertThat(aUnicaVaga().candidatas()).singleElement().satisfies(c -> {
            assertThat(c.cliente()).isEqualTo("Bia");
            assertThat(c.horarios()).containsExactly(as(14, 0), as(14, 15));
        });
    }

    @Test
    @DisplayName("com o lavatório tomado, o serviço que precisa dele não cabe")
    void recurso_tomado_nao_cabe() {
        // A Júlia está no lavatório das 14h às 15h. A vaga da Ana é das 14h às 14h45: a lavagem
        // não tem onde começar dentro dela; o corte tem.
        cenario();
        marcar(joana, julia, hidratacao, as(14, 0));
        var escovaDaCarla = marcar(carla, ana, escova, as(14, 0));
        esperar(marilda, lavagem, null);
        esperar(bia, corte, null);

        desmarcar(escovaDaCarla);

        assertThat(aUnicaVaga().candidatas()).extracting(VagaComCandidatas.Candidata::cliente)
                .containsExactly("Bia");
    }

    @Test
    @DisplayName("quem espera um serviço que a profissional da vaga não faz não é candidata")
    void servico_que_ela_nao_faz() {
        cenario();
        TenantContext.obter(tenant, () -> definirHabilidades.executar(ana, false,
                Set.of(coloracao, corte), Map.of()));
        esperar(marilda, escova, null);
        esperar(bia, corte, null);

        coloracaoDaCarlaCancelada();

        assertThat(aUnicaVaga().candidatas()).extracting(VagaComCandidatas.Candidata::cliente)
                .containsExactly("Bia");
    }

    @Test
    @DisplayName("uma espera de serviço que não existe mais não derruba a lista nem a chamada")
    void servico_inexistente_nao_derruba() throws SQLException {
        // A disponibilidade recusa serviço desconhecido com exceção, e a exceção dela marcaria a
        // transação de quem chama como perdida. A pergunta vem antes.
        cenario();
        try (var conexao = comoOwner(); var ps = conexao.prepareStatement(
                "insert into fila_espera (estabelecimento_id, cliente_id, servico_id, aceita_de,"
                        + " aceita_ate) values (?, ?, ?, ?, ?)")) {
            ps.setObject(1, tenant);
            ps.setObject(2, joana);
            ps.setObject(3, UUID.randomUUID());
            ps.setObject(4, DIA);
            ps.setObject(5, DIA);
            ps.executeUpdate();
        }
        var marildaEspera = esperar(marilda, corte, null);
        coloracaoDaCarlaCancelada();

        var vaga = aUnicaVaga();
        assertThat(vaga.candidatas()).extracting(VagaComCandidatas.Candidata::cliente)
                .containsExactly("Marilda");
        assertThat(chamar(vaga.id(), marildaEspera, ResultadoDaChamada.RECUSOU, null, null)
                .resultado()).isEqualTo(ResultadoDaChamada.RECUSOU);
    }

    @Test
    @DisplayName("as candidatas vêm na ordem de quem chegou primeiro")
    void candidatas_em_ordem_de_chegada() {
        cenario();
        esperar(marilda, corte, null);
        esperar(joana, escova, null);
        esperar(bia, corte, null);

        coloracaoDaCarlaCancelada();

        assertThat(aUnicaVaga().candidatas())
                .extracting(VagaComCandidatas.Candidata::cliente, VagaComCandidatas.Candidata::posicao)
                .containsExactly(tuple("Marilda", 1), tuple("Joana", 2), tuple("Bia", 3));
    }

    @Test
    @DisplayName("a faixa de hora filtra, em relógio de parede")
    void faixa_de_hora_filtra() {
        cenario();
        esperar(marilda, corte, null, DIA, DIA, LocalTime.of(9, 0), LocalTime.of(12, 0));
        esperar(joana, corte, null, DIA, DIA, LocalTime.of(15, 0), LocalTime.of(18, 0));

        coloracaoDaCarlaCancelada();

        assertThat(aUnicaVaga().candidatas()).singleElement().satisfies(c -> {
            assertThat(c.cliente()).isEqualTo("Joana");
            assertThat(c.horarios()).first().isEqualTo(as(15, 0));
        });
    }

    @Test
    @DisplayName("quem pediu outra profissional não é candidata, e chamá-la é recusado")
    void profissional_filtra_e_chamada_de_quem_nao_cabe() {
        cenario();
        var querJulia = esperar(bia, corte, julia);
        esperar(marilda, corte, ana);

        coloracaoDaCarlaCancelada();

        var vaga = aUnicaVaga();
        assertThat(vaga.candidatas()).extracting(VagaComCandidatas.Candidata::cliente)
                .containsExactly("Marilda");
        Throwable erro = erroAo(() -> chamar(vaga.id(), querJulia, ResultadoDaChamada.RECUSOU,
                null, null));
        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-ESPERA_NAO_CABE");
    }

    @Test
    @DisplayName("o dia da vaga precisa estar na janela de dias da espera")
    void janela_de_dias_filtra() {
        cenario();
        esperar(marilda, corte, null, DIA.plusDays(1), DIA.plusDays(3), null, null);
        esperar(joana, corte, null, DIA.minusDays(1), DIA, null, null);

        coloracaoDaCarlaCancelada();

        assertThat(aUnicaVaga().candidatas()).extracting(VagaComCandidatas.Candidata::cliente)
                .containsExactly("Joana");
    }

    @Test
    @DisplayName("quem saiu da fila não é candidata; vaga sem candidata não aparece")
    void quem_saiu_nao_e_candidata() {
        cenario();
        var espera = esperar(marilda, corte, null);
        TenantContext.executar(tenant, () -> fila.registrarDesistencia(espera.id()));

        coloracaoDaCarlaCancelada();

        assertThat(vagas()).isEmpty();
    }

    @Test
    @DisplayName("a vaga some quando o horário passa, e não aceita mais chamada")
    void vaga_passada_some_e_recusa_chamada() throws SQLException {
        // Sem job: a leitura filtra pelo fim, como a fila filtra pela janela (RN-AGD-023).
        cenario();
        var espera = esperar(marilda, corte, null, DIA.minusDays(8), DIA, null, null);
        var qualquerAgendamento = marcar(carla, ana, coloracao, as(14, 0));
        UUID vagaPassada = UUID.randomUUID();
        try (var conexao = comoOwner(); var ps = conexao.prepareStatement("""
                insert into vaga_aberta (id, estabelecimento_id, agendamento_id, origem,
                                         profissional_id, inicio, fim, aberta_em)
                values (?, ?, ?, 'CANCELAMENTO', ?, now() - interval '3 hours',
                        now() - interval '1 hour', now() - interval '1 day')
                """)) {
            ps.setObject(1, vagaPassada);
            ps.setObject(2, tenant);
            ps.setObject(3, qualquerAgendamento.id());
            ps.setObject(4, ana);
            ps.executeUpdate();
        }

        assertThat(vagas()).isEmpty();
        Throwable erro = erroAo(() -> chamar(vagaPassada, espera, ResultadoDaChamada.RECUSOU,
                null, null));
        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-VAGA_ENCERRADA");
    }

    // ------------------------------------------------------------------ aceitar é marcar

    @Test
    @DisplayName("aceitou: o agendamento é criado, ela sai da fila, e o resto da vaga continua")
    void aceitar_marca_e_tira_da_fila() throws SQLException {
        cenario();
        var marildaEspera = esperar(marilda, corte, null);
        esperar(joana, escova, null);
        coloracaoDaCarlaCancelada();
        var vaga = aUnicaVaga();

        var chamada = chamar(vaga.id(), marildaEspera, ResultadoDaChamada.ACEITOU, as(14, 0), null);

        assertThat(chamada.agendamentoId()).isNotNull();
        try (var conexao = comoOwner(); var ps = conexao.prepareStatement(
                "select cliente_id, profissional_id, servico_id, inicio from agendamento"
                        + " where id = ?")) {
            ps.setObject(1, chamada.agendamentoId());
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("cliente_id", UUID.class)).isEqualTo(marilda);
                assertThat(rs.getObject("profissional_id", UUID.class)).isEqualTo(ana);
                assertThat(rs.getObject("servico_id", UUID.class)).isEqualTo(corte);
                assertThat(rs.getObject("inicio", OffsetDateTime.class).toInstant())
                        .isEqualTo(as(14, 0));
            }
        }
        assertThat(naFila()).containsExactly(joana);
        // A coloração era de duas horas; o corte ocupa meia. A Joana continua cabendo, a partir
        // das 14h30.
        assertThat(aUnicaVaga().candidatas()).singleElement().satisfies(c -> {
            assertThat(c.cliente()).isEqualTo("Joana");
            assertThat(c.horarios()).first().isEqualTo(as(14, 30));
        });
    }

    @Test
    @DisplayName("horário tomado entre a tela e o clique: nada é gravado")
    void horario_tomado_nao_grava_chamada() throws SQLException {
        cenario();
        var marildaEspera = esperar(marilda, corte, null);
        coloracaoDaCarlaCancelada();
        var vaga = aUnicaVaga();

        // Alguém no balcão marca a Joana às 14h com a Ana, com a tela da fila aberta.
        marcar(joana, ana, corte, as(14, 0));
        Throwable erro = erroAo(() -> chamar(vaga.id(), marildaEspera, ResultadoDaChamada.ACEITOU,
                as(14, 0), null));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-HORARIO_FORA_DA_VAGA");
        assertThat(contar("chamada_da_fila")).isZero();
        assertThat(naFila()).containsExactly(marilda);
    }

    @Test
    @DisplayName("aceitou sem horário, ou com um horário fora da vaga, é recusado")
    void aceitar_sem_horario_da_vaga() throws SQLException {
        cenario();
        var marildaEspera = esperar(marilda, corte, null);
        coloracaoDaCarlaCancelada();
        UUID vaga = aUnicaVaga().id();

        Throwable semHorario = erroAo(() -> chamar(vaga, marildaEspera,
                ResultadoDaChamada.ACEITOU, null, null));
        Throwable foraDaVaga = erroAo(() -> chamar(vaga, marildaEspera,
                ResultadoDaChamada.ACEITOU, as(17, 0), null));

        assertThat(codigoDe(semHorario)).isEqualTo("ER-AGD-HORARIO_FORA_DA_VAGA");
        assertThat(codigoDe(foraDaVaga)).isEqualTo("ER-AGD-HORARIO_FORA_DA_VAGA");
        assertThat(contar("chamada_da_fila")).isZero();
    }

    // ------------------------------------------------------------------ a ordem e o pulo

    @Test
    @DisplayName("recusou: ela continua na fila, e a chamada aparece na linha dela")
    void recusou_continua_na_fila() {
        cenario();
        var marildaEspera = esperar(marilda, corte, null);
        coloracaoDaCarlaCancelada();
        UUID vaga = aUnicaVaga().id();

        var chamada = chamar(vaga, marildaEspera, ResultadoDaChamada.RECUSOU, null, null);

        assertThat(chamada.agendamentoId()).isNull();
        assertThat(naFila()).containsExactly(marilda);
        assertThat(aUnicaVaga().candidatas().get(0).chamadas())
                .extracting(VagaComCandidatas.Chamada::resultado)
                .containsExactly(ResultadoDaChamada.RECUSOU);
    }

    @Test
    @DisplayName("não atendeu conta como chamada: seguir para a próxima não é pulo")
    void nao_atendeu_conta_como_chamada() {
        cenario();
        var primeira = esperar(marilda, corte, null);
        var segunda = esperar(joana, corte, null);
        coloracaoDaCarlaCancelada();
        UUID vaga = aUnicaVaga().id();

        chamar(vaga, primeira, ResultadoDaChamada.NAO_ATENDEU, null, null);
        assertThat(aUnicaVaga().candidatas())
                .extracting(VagaComCandidatas.Candidata::exigeMotivo).containsExactly(false, false);
        var chamada = chamar(vaga, segunda, ResultadoDaChamada.RECUSOU, null, null);

        assertThat(chamada.motivoDoPulo()).isNull();
        assertThat(naFila()).containsExactly(marilda, joana);
    }

    @Test
    @DisplayName("chamar fora da ordem sem motivo é recusado")
    void pular_sem_motivo_e_recusado() throws SQLException {
        cenario();
        esperar(marilda, corte, null);
        var segunda = esperar(joana, corte, null);
        coloracaoDaCarlaCancelada();
        UUID vaga = aUnicaVaga().id();

        Throwable semMotivo = erroAo(() -> chamar(vaga, segunda, ResultadoDaChamada.RECUSOU,
                null, null));
        Throwable emBranco = erroAo(() -> chamar(vaga, segunda, ResultadoDaChamada.RECUSOU,
                null, "  "));

        assertThat(aUnicaVaga().candidatas())
                .as("a tela sabe antes de clicar: só a 2ª exige motivo")
                .extracting(VagaComCandidatas.Candidata::exigeMotivo).containsExactly(false, true);
        assertThat(codigoDe(semMotivo)).isEqualTo("ER-AGD-PULO_SEM_MOTIVO");
        assertThat(codigoDe(emBranco)).isEqualTo("ER-AGD-PULO_SEM_MOTIVO");
        assertThat(((ErroDeDominio) semMotivo).getMessage())
                .as("a posição, e não o nome: detalhe de erro pode ir para log")
                .contains("1ª").doesNotContain("Marilda");
        assertThat(contar("chamada_da_fila")).isZero();
    }

    @Test
    @DisplayName("chamar fora da ordem com motivo: o motivo fica gravado e aparece na linha")
    void pular_com_motivo_fica_gravado() {
        cenario();
        esperar(marilda, corte, null);
        var segunda = esperar(joana, corte, null);
        coloracaoDaCarlaCancelada();
        UUID vaga = aUnicaVaga().id();

        var chamada = chamar(vaga, segunda, ResultadoDaChamada.RECUSOU, null,
                "  a Marilda só pode de manhã ");

        assertThat(chamada.motivoDoPulo()).isEqualTo("a Marilda só pode de manhã");
        assertThat(aUnicaVaga().candidatas().get(1).chamadas())
                .extracting(VagaComCandidatas.Chamada::motivoDoPulo)
                .containsExactly("a Marilda só pode de manhã");
    }

    @Test
    @DisplayName("sem pulo, o motivo informado não é gravado")
    void sem_pulo_motivo_nao_e_gravado() {
        cenario();
        var primeira = esperar(marilda, corte, null);
        coloracaoDaCarlaCancelada();

        var chamada = chamar(aUnicaVaga().id(), primeira, ResultadoDaChamada.RECUSOU, null,
                "texto sem propósito");

        assertThat(chamada.motivoDoPulo()).isNull();
    }

    // ------------------------------------------------------------------ o dia da série

    @Test
    @DisplayName("o dia da série que não coube vai para a fila, e vira candidata quando abre vaga")
    void dia_da_serie_vai_para_a_fila() {
        cenario();
        LocalDate segunda = DIA.plusDays(7);
        var corteDaJoana = marcar(joana, ana, corte, em(segunda, 14, 0));
        SerieCriada serie = TenantContext.obter(tenant, () -> criarSerie.executar(marilda, ana,
                escova, as(14, 0), Frequencia.SEMANAL, 3, null));
        assertThat(serie.naoCouberam()).containsExactly(em(segunda, 14, 0));
        UUID serieId = serie.recorrencia().id();

        var espera = TenantContext.obter(tenant, () -> enfileirarDia.executar(serieId, segunda));

        assertThat(espera.clienteId()).isEqualTo(marilda);
        assertThat(espera.servicoId()).isEqualTo(escova);
        assertThat(espera.profissionalId()).isEqualTo(ana);
        assertThat(espera.aceitaDe()).isEqualTo(segunda);
        assertThat(espera.aceitaAte()).isEqualTo(segunda);
        assertThat(espera.horaDe()).as("a recepção confirma a hora ao ligar").isNull();
        assertThat(espera.recorrenciaId()).isEqualTo(serieId);

        desmarcar(corteDaJoana);
        assertThat(aUnicaVaga().candidatas()).singleElement()
                .satisfies(c -> assertThat(c.esperaId()).isEqualTo(espera.id()));
    }

    @Test
    @DisplayName("dia que coube, fora do ritmo da série, ou já na fila, é recusado")
    void dia_fora_da_serie() {
        cenario();
        LocalDate segunda = DIA.plusDays(7);
        marcar(joana, ana, corte, em(segunda, 14, 0));
        UUID serieId = TenantContext.obter(tenant, () -> criarSerie.executar(marilda, ana, escova,
                as(14, 0), Frequencia.SEMANAL, 3, null)).recorrencia().id();
        TenantContext.obter(tenant, () -> enfileirarDia.executar(serieId, segunda));

        Throwable coube = erroAo(() -> TenantContext.obter(tenant,
                () -> enfileirarDia.executar(serieId, DIA)));
        Throwable foraDoRitmo = erroAo(() -> TenantContext.obter(tenant,
                () -> enfileirarDia.executar(serieId, DIA.plusDays(3))));
        Throwable deNovo = erroAo(() -> TenantContext.obter(tenant,
                () -> enfileirarDia.executar(serieId, segunda)));

        Throwable depoisDoTeto = erroAo(() -> TenantContext.obter(tenant,
                () -> enfileirarDia.executar(serieId, DIA.plusWeeks(52))));

        assertThat(codigoDe(coube)).isEqualTo("ER-AGD-DIA_FORA_DA_SERIE");
        assertThat(codigoDe(foraDoRitmo)).isEqualTo("ER-AGD-DIA_FORA_DA_SERIE");
        assertThat(codigoDe(deNovo)).isEqualTo("ER-AGD-JA_ESTA_NA_FILA");
        assertThat(codigoDe(depoisDoTeto)).as("nenhuma série passa de 52 ocorrências")
                .isEqualTo("ER-AGD-DIA_FORA_DA_SERIE");
        assertThatCode(() -> TenantContext.obter(tenant,
                () -> enfileirarDia.executar(serieId, DIA.plusWeeks(51))))
                .as("a 52ª ainda é da série").doesNotThrowAnyException();
    }

    @Test
    @DisplayName("dia que já passou, ou de série encerrada, não vai para a fila")
    void dia_passado_ou_serie_encerrada() throws SQLException {
        cenario();
        UUID serieId = TenantContext.obter(tenant, () -> criarSerie.executar(marilda, ana, escova,
                as(14, 0), Frequencia.SEMANAL, 2, null)).recorrencia().id();
        // A série "começou" duas semanas antes: o primeiro dia do ritmo ficou no passado, sem
        // agendamento nele — só a data é que não serve mais.
        try (var conexao = comoOwner(); var ps = conexao.prepareStatement(
                "update recorrencia set primeiro_inicio = primeiro_inicio - interval '14 days'"
                        + " where id = ?")) {
            ps.setObject(1, serieId);
            ps.executeUpdate();
        }

        Throwable passado = erroAo(() -> TenantContext.obter(tenant,
                () -> enfileirarDia.executar(serieId, DIA.minusDays(14))));
        TenantContext.executar(tenant, () -> encerrarSerie.executar(serieId, "cliente mudou"));
        Throwable encerrada = erroAo(() -> TenantContext.obter(tenant,
                () -> enfileirarDia.executar(serieId, DIA.plusWeeks(3))));

        assertThat(codigoDe(passado)).isEqualTo("ER-AGD-DIA_FORA_DA_SERIE");
        assertThat(((ErroDeDominio) passado).getMessage()).contains("passou");
        assertThat(codigoDe(encerrada)).isEqualTo("ER-AGD-DIA_FORA_DA_SERIE");
        assertThat(((ErroDeDominio) encerrada).getMessage()).contains("encerrada");
    }

    // ------------------------------------------------------------------ isolamento e permissão

    @Test
    @DisplayName("um salão não vê nem chama na vaga do outro")
    void isolamento_entre_saloes() {
        cenario();
        var espera = esperar(marilda, corte, null);
        coloracaoDaCarlaCancelada();
        UUID vagaDeA = aUnicaVaga().id();
        UUID salaoB = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Outra",
                "outra-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        assertThat(TenantContext.obter(salaoB, () -> vagasAbertas.executar())).isEmpty();
        Throwable erro = erroAo(() -> TenantContext.obter(salaoB, () -> registrar.executar(
                vagaDeA, espera.id(), ResultadoDaChamada.RECUSOU, null, null)));
        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-VAGA_NAO_ENCONTRADA");
    }

    @Test
    @DisplayName("ler as vagas exige a agenda de todos; registrar, escrever nela")
    void permissao() {
        cenario();
        var espera = esperar(marilda, corte, null);
        coloracaoDaCarlaCancelada();
        UUID vaga = aUnicaVaga().id();

        Throwable soLeAPropria = comPermissoes(() -> erroAo(() -> vagas()),
                Permissao.AGENDA_LER_PROPRIA);
        Throwable soLe = comPermissoes(() -> erroAo(() -> chamar(vaga, espera,
                ResultadoDaChamada.RECUSOU, null, null)), Permissao.AGENDA_LER_TODAS);

        assertThat(soLeAPropria).as("a lista traz telefone de quem espera qualquer profissional")
                .isInstanceOf(AccessDeniedException.class);
        assertThat(soLe).isInstanceOf(AccessDeniedException.class);
    }

    /**
     * Roda com outras permissões só a ação, e não o cenário: {@code @WithMockUser} no método
     * valeria também para o cadastro que monta a vaga.
     */
    private static <T> T comPermissoes(Supplier<T> acao, String... permissoes) {
        var antes = SecurityContextHolder.getContext();
        var contexto = SecurityContextHolder.createEmptyContext();
        var autenticacao = new TestingAuthenticationToken("recepcao", null, permissoes);
        autenticacao.setAuthenticated(true);
        contexto.setAuthentication(autenticacao);
        SecurityContextHolder.setContext(contexto);
        try {
            return acao.get();
        } finally {
            SecurityContextHolder.setContext(antes);
        }
    }
}
