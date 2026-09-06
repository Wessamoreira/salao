package br.com.salao.atendimento.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.agenda.api.OrigemDoAgendamento;
import br.com.salao.agenda.internal.application.CancelarAgendamentoUseCase;
import br.com.salao.agenda.internal.application.CriarAgendamentoUseCase;
import br.com.salao.agenda.internal.application.NovoAgendamento;
import br.com.salao.agenda.internal.application.RegistrarNoShowUseCase;
import br.com.salao.atendimento.api.OrigemDaComanda;
import br.com.salao.atendimento.api.StatusDaComanda;
import br.com.salao.atendimento.internal.infra.ComandasJdbc;
import br.com.salao.catalogo.internal.application.EtapaBruta;
import br.com.salao.catalogo.api.TipoDeBloco;
import br.com.salao.catalogo.internal.application.CadastrarServicoUseCase;
import br.com.salao.cliente.internal.application.CadastrarClienteUseCase;
import br.com.salao.cliente.internal.application.DadosDoCliente;
import br.com.salao.equipe.internal.application.DadosDoProfissional;
import br.com.salao.equipe.internal.application.PeriodoBruto;
import br.com.salao.equipe.api.TipoDeVinculo;
import br.com.salao.equipe.internal.application.CadastrarProfissionalUseCase;
import br.com.salao.equipe.internal.application.DefinirJornadaUseCase;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-ATD-001 — abrir a conta do atendimento. */
@WithMockUser(authorities = { Permissao.COMANDA_ABRIR, Permissao.COMANDA_FECHAR,
        Permissao.CLIENTE_ESCREVER, Permissao.USUARIO_GERENCIAR,
        Permissao.AGENDA_ESCREVER_TODAS, Permissao.CONFIG_GERENCIAR })
class AbrirComandaIT extends AbstractPostgresIT {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarClienteUseCase cadastrarCliente;
    @Autowired private CadastrarProfissionalUseCase cadastrarProfissional;
    @Autowired private DefinirJornadaUseCase definirJornada;
    @Autowired private CadastrarServicoUseCase cadastrarServico;
    @Autowired private CriarAgendamentoUseCase criarAgendamento;
    @Autowired private CancelarAgendamentoUseCase cancelar;
    @Autowired private RegistrarNoShowUseCase noShow;
    @Autowired private AbrirComandaUseCase abrir;
    @Autowired private ListarComandasAbertasUseCase abertas;
    @Autowired private ComandasJdbc comandas;

    private UUID salao() {
        return provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
    }

    private UUID cliente(UUID tenant, String nome) {
        return TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                new DadosDoCliente(nome, null, null, null, null)));
    }

    /** Profissional e serviço de cada salão, criados uma vez: o CPF é único por salão. */
    private final Map<UUID, UUID[]> equipeDoSalao = new HashMap<>();

    private UUID agendamentoDe(UUID tenant, UUID clienteId) {
        return agendamentoDe(tenant, clienteId, 10);
    }

    /**
     * Profissional com jornada larga e um serviço — o mínimo para existir um agendamento.
     *
     * <p>Amanhã em hora fixa, e não "daqui a três horas": relativo ao agora, o horário cai fora da
     * jornada sempre que o teste roda de madrugada — e o build passa a depender da hora do dia.
     */
    private UUID agendamentoDe(UUID tenant, UUID clienteId, int hora) {
        UUID[] equipe = equipeDoSalao.computeIfAbsent(tenant, this::prepararEquipe);
        UUID ana = equipe[0];
        UUID escova = equipe[1];
        Instant quando = LocalDate.now(SAO_PAULO).plusDays(1)
                .atTime(hora, 0).atZone(SAO_PAULO).toInstant();
        return TenantContext.obter(tenant, () -> criarAgendamento.executar(new NovoAgendamento(
                clienteId, ana, escova, quando, OrigemDoAgendamento.PAINEL, null, null,
                false, false, null, null))).id();
    }

    private UUID[] prepararEquipe(UUID tenant) {
        UUID ana = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional("Ana Ribeiro", "Ana", "11144477735",
                        TipoDeVinculo.EMPREGADO, null, null, null, null, null, null))).id();
        var semana = new HashMap<DayOfWeek, List<PeriodoBruto>>();
        for (DayOfWeek dia : DayOfWeek.values()) {
            semana.put(dia, List.of(new PeriodoBruto(LocalTime.of(6, 0), LocalTime.of(23, 0))));
        }
        TenantContext.executar(tenant, () -> definirJornada.executar(ana, semana));
        UUID escova = TenantContext.obter(tenant, () -> cadastrarServico.executar("Escova", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 45)))).id();
        return new UUID[] { ana, escova };
    }

    /** Empurra o horário para ontem: ninguém falta a um atendimento que ainda não começou. */
    private void jogarParaOntem(UUID agendamentoId) throws SQLException {
        try (var ps = comoOwner().prepareStatement(
                "update agendamento set inicio = inicio - interval '2 days',"
                        + " fim = fim - interval '2 days' where id = ?")) {
            ps.setObject(1, agendamentoId);
            ps.executeUpdate();
        }
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ o caminho normal

    @Test
    @DisplayName("abre avulsa, e o número começa em 1")
    void abre_avulsa() {
        UUID tenant = salao();
        UUID maria = cliente(tenant, "Maria Silva");

        var abertura = TenantContext.obter(tenant, () -> abrir.avulsa(maria));

        assertThat(abertura.jaEstavaAberta()).isFalse();
        assertThat(abertura.comanda().numero()).isEqualTo(1);
        assertThat(abertura.comanda().status()).isEqualTo(StatusDaComanda.ABERTA);
        assertThat(abertura.comanda().origem()).isEqualTo(OrigemDaComanda.AVULSA);
        assertThat(abertura.comanda().clienteId()).isEqualTo(maria);
    }

    @Test
    @DisplayName("do agendamento: a comanda é da cliente do horário")
    void abre_do_agendamento() {
        UUID tenant = salao();
        UUID maria = cliente(tenant, "Maria Silva");
        UUID horario = agendamentoDe(tenant, maria);

        var abertura = TenantContext.obter(tenant, () -> abrir.doAgendamento(horario));

        assertThat(abertura.comanda().clienteId()).isEqualTo(maria);
        assertThat(abertura.comanda().origem()).isEqualTo(OrigemDaComanda.AGENDAMENTO);
    }

    @Test
    @DisplayName("o número anda de um em um, sem buraco")
    void numeros_sequenciais() {
        UUID tenant = salao();
        UUID maria = cliente(tenant, "Maria Silva");
        UUID joana = cliente(tenant, "Joana Prado");

        long primeira = TenantContext.obter(tenant, () -> abrir.avulsa(maria)).comanda().numero();
        long segunda = TenantContext.obter(tenant, () -> abrir.avulsa(joana)).comanda().numero();

        assertThat(primeira).isEqualTo(1);
        assertThat(segunda).isEqualTo(2);
    }

    // ------------------------------------------------------------------ o que a rotina protege

    @Test
    @DisplayName("abrir de novo devolve a MESMA comanda, e diz que já estava aberta")
    void abrir_duas_vezes_e_a_mesma() {
        // Duas comandas abertas para a mesma pessoa é o defeito que ninguém percebe na hora:
        // metade dos itens cai numa, metade na outra, e o salão cobra uma só.
        UUID tenant = salao();
        UUID maria = cliente(tenant, "Maria Silva");

        var primeira = TenantContext.obter(tenant, () -> abrir.avulsa(maria));
        var segunda = TenantContext.obter(tenant, () -> abrir.avulsa(maria));

        assertThat(segunda.comanda().id()).isEqualTo(primeira.comanda().id());
        assertThat(segunda.jaEstavaAberta())
                .as("a tela precisa saber que não abriu nada agora").isTrue();
    }

    @Test
    @DisplayName("o segundo horário do dia cai na comanda que já está aberta")
    void segundo_horario_mesma_comanda() {
        // Corte às 9h e coloração às 14h é uma conta só. Abrir a segunda comanda faria a cliente
        // pagar duas vezes ou o salão perder uma.
        UUID tenant = salao();
        UUID maria = cliente(tenant, "Maria Silva");
        UUID manha = agendamentoDe(tenant, maria, 9);
        UUID tarde = agendamentoDe(tenant, maria, 14);

        var primeira = TenantContext.obter(tenant, () -> abrir.doAgendamento(manha));
        var segunda = TenantContext.obter(tenant, () -> abrir.doAgendamento(tarde));

        assertThat(segunda.comanda().id()).isEqualTo(primeira.comanda().id());
        assertThat(segunda.jaEstavaAberta()).isTrue();
    }

    @Test
    @DisplayName("agendamento cancelado não vira comanda")
    void cancelado_nao_abre() {
        // A comanda diria que aconteceu um atendimento que o próprio sistema registrou como
        // desmarcado — e é dessa contradição que sai o faturamento que não bate.
        UUID tenant = salao();
        UUID maria = cliente(tenant, "Maria Silva");
        UUID horario = agendamentoDe(tenant, maria);
        var cancelado = TenantContext.obter(tenant,
                () -> cancelar.executar(horario, 0, "cliente desmarcou", true));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> abrir.doAgendamento(cancelado.id())));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-AGENDAMENTO_CANCELADO");
    }

    @Test
    @DisplayName("no-show ABRE: quem está no balcão desmente o registro")
    void no_show_abre() throws SQLException {
        // Recusar obrigaria a recepção a corrigir o status antes de poder atender alguém que já
        // está sentado na cadeira. A comanda é a prova de que o no-show estava errado.
        UUID tenant = salao();
        UUID maria = cliente(tenant, "Maria Silva");
        UUID horario = agendamentoDe(tenant, maria);
        jogarParaOntem(horario);
        var faltou = TenantContext.obter(tenant, () -> noShow.executar(horario, 0));

        var abertura = TenantContext.obter(tenant, () -> abrir.doAgendamento(faltou.id()));

        assertThat(abertura.comanda().clienteId()).isEqualTo(maria);
    }

    @Test
    @DisplayName("cliente que não existe não abre comanda")
    void cliente_inexistente() {
        UUID tenant = salao();

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> abrir.avulsa(UUID.randomUUID())));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-CLIENTE_NAO_ENCONTRADO");
    }

    @Test
    @DisplayName("agendamento de outro salão não existe para este")
    void agendamento_de_outro_salao() {
        // Não é 403 e sim 404: para este salão, aquele agendamento simplesmente não existe — e a
        // diferença entre "não existe" e "não é seu" é a que conta quantas comandas o vizinho tem.
        UUID salaoA = salao();
        UUID salaoB = salao();
        UUID mariaDoB = cliente(salaoB, "Maria Silva");
        UUID horarioDoB = agendamentoDe(salaoB, mariaDoB);

        Throwable erro = catchThrowable(() -> TenantContext.executar(salaoA,
                () -> abrir.doAgendamento(horarioDoB)));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-AGENDAMENTO_NAO_ENCONTRADO");
    }

    @Test
    @DisplayName("cada salão numera as suas comandas, do 1")
    void numeracao_por_salao() {
        // Uma sequence do banco seria global: o 42 de um salão gastaria o 42 do outro, e o
        // tamanho do salto contaria ao vizinho quantas comandas cada um abre.
        UUID salaoA = salao();
        UUID salaoB = salao();
        UUID mariaDoA = cliente(salaoA, "Maria Silva");
        UUID joanaDoB = cliente(salaoB, "Joana Prado");

        TenantContext.executar(salaoA, () -> abrir.avulsa(mariaDoA));
        long primeiraDoB = TenantContext.obter(salaoB,
                () -> abrir.avulsa(joanaDoB)).comanda().numero();

        assertThat(primeiraDoB).isEqualTo(1);
    }

    @Test
    @DisplayName("um salão não vê a comanda aberta do outro")
    void isolamento_entre_saloes() {
        UUID salaoA = salao();
        UUID salaoB = salao();
        UUID mariaDoA = cliente(salaoA, "Maria Silva");
        TenantContext.executar(salaoA, () -> abrir.avulsa(mariaDoA));

        assertThat(TenantContext.obter(salaoB, () -> abertas.executar())).isEmpty();
    }

    @Test
    @DisplayName("quem recusa a segunda comanda é o ÍNDICE, não a consulta que veio antes")
    void o_indice_e_que_decide() {
        // O caso de uso confere antes de inserir, mas entre a consulta e o insert cabe outra
        // transação — e é na hora do movimento que ela cabe. Aqui a consulta é pulada de
        // propósito: o insert vai direto, e quem diz não é o índice parcial.
        UUID tenant = salao();
        UUID maria = cliente(tenant, "Maria Silva");
        TenantContext.executar(tenant, () -> abrir.avulsa(maria));

        var segunda = TenantContext.obter(tenant, () -> comandas.inserirSeNaoHouverAberta(
                tenant, maria, 99, OrigemDaComanda.AVULSA, Instant.now(), null));

        assertThat(segunda).as("o banco não deixou nascer a segunda").isEmpty();
        assertThat(TenantContext.obter(tenant, () -> abertas.executar())).hasSize(1);
    }

    // ------------------------------------------------------------------ a barra do balcão

    @Test
    @DisplayName("a barra lista as abertas com o nome, da mais antiga para a mais nova")
    void barra_de_comandas_abertas() {
        // "Comanda aberta esquecida é dinheiro que sai pela porta" (R-UX-07): a mais antiga é a
        // que interessa, então é a que vem primeiro.
        UUID tenant = salao();
        UUID maria = cliente(tenant, "Maria Silva");
        UUID joana = cliente(tenant, "Joana Prado");
        TenantContext.executar(tenant, () -> abrir.avulsa(maria));
        TenantContext.executar(tenant, () -> abrir.avulsa(joana));

        var barra = TenantContext.obter(tenant, () -> abertas.executar());

        assertThat(barra).hasSize(2);
        assertThat(barra.get(0).clienteNome()).isEqualTo("Maria Silva");
        assertThat(barra.get(1).clienteNome()).isEqualTo("Joana Prado");
    }
}
