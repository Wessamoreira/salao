package br.com.salao.agenda.internal.infra;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.agenda.api.OrigemDoAgendamento;
import br.com.salao.agenda.api.StatusAgendamento;
import br.com.salao.agenda.internal.application.CriarAgendamentoUseCase;
import br.com.salao.agenda.internal.application.NovoAgendamento;
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
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-NOT-002 — o lembrete de 24h. */
@WithMockUser(authorities = { Permissao.USUARIO_GERENCIAR, Permissao.CONFIG_GERENCIAR,
        Permissao.CLIENTE_ESCREVER, Permissao.AGENDA_ESCREVER_TODAS })
class LembradorDeAgendamentosIT extends AbstractPostgresIT {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarProfissionalUseCase cadastrarProfissional;
    @Autowired private DefinirJornadaUseCase definirJornada;
    @Autowired private CadastrarServicoUseCase cadastrarServico;
    @Autowired private CadastrarClienteUseCase cadastrarCliente;
    @Autowired private CriarAgendamentoUseCase criar;
    @Autowired private LembradorDeAgendamentos lembrador;

    private UUID tenant;
    private UUID ana;
    private UUID marilda;
    private UUID escova;

    private LocalDate emDias(int dias) {
        return LocalDate.now(SAO_PAULO).plusDays(dias);
    }

    private void cenario(String telefone) {
        tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Studio Marilda", null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        ana = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional("Ana Ribeiro", "Ana", "11144477735",
                        TipoDeVinculo.EMPREGADO, null, null, null, null, null, null))).id();
        // Jornada larga em todos os dias: o teste marca em datas variadas.
        var semana = new HashMap<DayOfWeek, List<PeriodoBruto>>();
        for (DayOfWeek dia : DayOfWeek.values()) {
            semana.put(dia, List.of(new PeriodoBruto(LocalTime.of(6, 0), LocalTime.of(23, 0))));
        }
        TenantContext.executar(tenant, () -> definirJornada.executar(ana, semana));
        marilda = TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                new DadosDoCliente("Marilda Souza", null, telefone, null, null)));
        escova = TenantContext.obter(tenant, () -> cadastrarServico.executar("Escova", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 45)))).id();
    }

    private UUID marcarEm(Instant quando) {
        return TenantContext.obter(tenant, () -> criar.executar(new NovoAgendamento(
                marilda, ana, escova, quando, OrigemDoAgendamento.PAINEL, null, null,
                false, false, null, null))).id();
    }

    /** Daqui a N horas, arredondado para a hora cheia — sempre dentro da jornada larga. */
    private Instant daquiA(int horas) {
        return Instant.now().plusSeconds(horas * 3600L)
                .atZone(SAO_PAULO).withMinute(0).withSecond(0).withNano(0).toInstant();
    }

    /** O agendamento precisa parecer velho: quem acabou de marcar não é lembrada. */
    private void envelhecer(UUID agendamentoId) throws SQLException {
        try (var ps = comoOwner().prepareStatement(
                "update agendamento set criado_em = now() - interval '3 hours' where id = ?")) {
            ps.setObject(1, agendamentoId);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ o caso da rotina

    @Test
    @DisplayName("o horário de amanhã vira lembrete")
    void lembra_do_dia_seguinte() throws SQLException {
        cenario("11987654321");
        UUID amanha = marcarEm(daquiA(20));
        envelhecer(amanha);

        int enfileirados = lembrador.executar();

        assertThat(enfileirados).isEqualTo(1);
        assertThat(parametrosDe(amanha)).contains("Marilda")
                .contains("Studio Marilda").contains("Ana");
    }

    @Test
    @DisplayName("a mensagem diz a hora de um jeito que se lê sem converter")
    void quando_legivel() throws SQLException {
        // "18/09 às 14:00" obriga quem lê a converter, e a conversão é onde ela erra o dia.
        cenario("11987654321");
        UUID amanha = marcarEm(daquiA(20));
        envelhecer(amanha);

        lembrador.executar();

        assertThat(parametroDe(amanha, "quando")).matches("(hoje|amanhã) às \\d{1,2}h");
    }

    @Test
    @DisplayName("rodar duas vezes não manda duas mensagens")
    void nao_duplica() throws SQLException {
        // A janela é larga de propósito, então a mesma cliente é alcançada em execuções seguidas.
        // Quem impede a duplicata é o índice único da referência (RN-NOT-003), não a precisão do
        // agendamento do job.
        cenario("11987654321");
        UUID amanha = marcarEm(daquiA(20));
        envelhecer(amanha);

        lembrador.executar();
        lembrador.executar();

        assertThat(quantasMensagens()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ o que fica de fora

    @Test
    @DisplayName("quem acabou de marcar não é lembrada")
    void recem_marcado_nao_lembra() {
        // Ela combinou o horário no balcão há dez minutos: uma mensagem dizendo o que ela acabou
        // de combinar é ruído.
        cenario("11987654321");
        marcarEm(daquiA(20));

        assertThat(lembrador.executar()).isZero();
    }

    @Test
    @DisplayName("daqui a uma semana ainda não é lembrete")
    void longe_demais_nao_lembra() throws SQLException {
        cenario("11987654321");
        UUID longe = marcarEm(daquiA(24 * 7));
        envelhecer(longe);

        assertThat(lembrador.executar()).isZero();
    }

    @Test
    @DisplayName("daqui a duas horas já passou da janela")
    void perto_demais_nao_lembra() throws SQLException {
        // A cliente está vindo: um lembrete agora chega junto com ela.
        cenario("11987654321");
        UUID perto = marcarEm(daquiA(2));
        envelhecer(perto);

        assertThat(lembrador.executar()).isZero();
    }

    @Test
    @DisplayName("cancelado não é lembrado")
    void cancelado_nao_lembra() throws SQLException {
        // Lembrar de um cancelado faz a cliente aparecer para um horário que não existe.
        cenario("11987654321");
        UUID amanha = marcarEm(daquiA(20));
        envelhecer(amanha);
        forcarStatus(amanha, StatusAgendamento.CANCELADO);

        assertThat(lembrador.executar()).isZero();
    }

    @Test
    @DisplayName("cliente sem telefone não quebra o lote")
    void sem_telefone_nao_quebra() throws SQLException {
        // Cadastro incompleto é o caso comum e não é erro do sistema. O que não pode é a cliente
        // sem telefone impedir o lembrete das outras.
        cenario(null);
        UUID amanha = marcarEm(daquiA(20));
        envelhecer(amanha);

        assertThat(lembrador.executar()).isZero();
        assertThat(quantasMensagens()).isZero();
    }

    @Test
    @DisplayName("o laço atravessa mais de um salão")
    void varios_saloes() throws SQLException {
        // Mesmo padrão do reenviador de eventos: tenant a tenant, com o erro contido.
        cenario("11987654321");
        UUID amanha = marcarEm(daquiA(20));
        envelhecer(amanha);
        provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Outro", null, "Outra", "outra-" + UUID.randomUUID() + "@salao.test",
                "senha-bem-comprida-1"));

        assertThat(lembrador.executar()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ auxiliares

    private void forcarStatus(UUID id, StatusAgendamento status) throws SQLException {
        try (var ps = comoOwner().prepareStatement(
                "update agendamento set status = ? where id = ?")) {
            ps.setString(1, status.name());
            ps.setObject(2, id);
            ps.executeUpdate();
        }
    }

    private int quantasMensagens() throws SQLException {
        try (var ps = comoOwner().prepareStatement(
                "select count(*) from mensagem where estabelecimento_id = ?")) {
            ps.setObject(1, tenant);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private String parametrosDe(UUID agendamentoId) throws SQLException {
        try (var ps = comoOwner().prepareStatement(
                "select parametros::text from mensagem where referencia = ?")) {
            ps.setObject(1, agendamentoId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private String parametroDe(UUID agendamentoId, String chave) throws SQLException {
        try (var ps = comoOwner().prepareStatement(
                "select parametros->>? from mensagem where referencia = ?")) {
            ps.setString(1, chave);
            ps.setObject(2, agendamentoId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
