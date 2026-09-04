package br.com.salao.agenda.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.agenda.api.AgendamentoConfirmado;
import br.com.salao.agenda.api.AgendamentoResumo;
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

/** RT-AGD-004 — confirmar agendamento. */
@WithMockUser(authorities = { Permissao.USUARIO_GERENCIAR, Permissao.CONFIG_GERENCIAR,
        Permissao.CLIENTE_ESCREVER, Permissao.AGENDA_LER_TODAS,
        Permissao.AGENDA_ESCREVER_TODAS })
@RecordApplicationEvents
class ConfirmarAgendamentoIT extends AbstractPostgresIT {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate DIA = LocalDate.now(SAO_PAULO).plusDays(7);

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarProfissionalUseCase cadastrarProfissional;
    @Autowired private DefinirJornadaUseCase definirJornada;
    @Autowired private CadastrarServicoUseCase cadastrarServico;
    @Autowired private CadastrarClienteUseCase cadastrarCliente;
    @Autowired private CriarAgendamentoUseCase criar;
    @Autowired private ConfirmarAgendamentoUseCase confirmar;
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
        TenantContext.executar(tenant, () -> definirJornada.executar(ana,
                Map.of(DIA.getDayOfWeek(),
                        List.of(new PeriodoBruto(LocalTime.of(9, 0), LocalTime.of(18, 0))))));
        marilda = TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                new DadosDoCliente("Marilda", null, null, null, null)));
        // Três blocos: é o que permite ver se os blocos acompanham a cabeça (RN-AGD-006).
        mechas = TenantContext.obter(tenant, () -> cadastrarServico.executar("Mechas", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 30),
                        new EtapaBruta(TipoDeBloco.PAUSA, 40),
                        new EtapaBruta(TipoDeBloco.ATIVO, 30)))).id();
    }

    private Instant as(int hora) {
        return DIA.atTime(LocalTime.of(hora, 0)).atZone(SAO_PAULO).toInstant();
    }

    /** Pela web nasce SOLICITADO — é o que dá o que confirmar. */
    private AgendamentoResumo solicitado(int hora) {
        return TenantContext.obter(tenant, () -> criar.executar(new NovoAgendamento(
                marilda, ana, mechas, as(hora), OrigemDoAgendamento.WEB, null, null,
                false, false, null, null)));
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    @Test
    @DisplayName("solicitado vira confirmado, e os blocos acompanham")
    void confirma_e_os_blocos_acompanham() throws SQLException {
        // RN-AGD-006. Confirmar não muda a ocupação — os dois status ocupam a agenda — mas o
        // caminho precisa ser o mesmo de cancelar, onde muda. Se os blocos ficassem para trás
        // aqui, ficariam para trás lá, e um cancelado seguiria bloqueando o horário.
        cenario();
        var criado = solicitado(13);
        assertThat(criado.status()).isEqualTo(StatusAgendamento.SOLICITADO);

        var confirmado = TenantContext.obter(tenant,
                () -> confirmar.executar(criado.id(), criado.versao(), false));

        assertThat(confirmado.status()).isEqualTo(StatusAgendamento.CONFIRMADO);
        assertThat(statusDosBlocos(criado.id()))
                .as("os três blocos, inclusive a pausa")
                .containsExactly("CONFIRMADO", "CONFIRMADO", "CONFIRMADO");
    }

    @Test
    @DisplayName("confirmar de novo não é erro, e não gera evento novo")
    void confirmar_duas_vezes() {
        // A cliente clica duas vezes no link do lembrete. O estado desejado já vale: devolver
        // erro mostraria falha para quem fez tudo certo. A transição continua não existindo — o
        // que muda é a resposta, não a regra.
        cenario();
        var criado = solicitado(13);
        var primeira = TenantContext.obter(tenant,
                () -> confirmar.executar(criado.id(), criado.versao(), true));

        var segunda = TenantContext.obter(tenant,
                () -> confirmar.executar(criado.id(), primeira.versao(), true));

        assertThat(segunda.status()).isEqualTo(StatusAgendamento.CONFIRMADO);
        assertThat(segunda.versao()).as("nada foi gravado").isEqualTo(primeira.versao());
        assertThat(eventos.stream(AgendamentoConfirmado.class).count())
                .as("um evento, não dois").isEqualTo(1);
    }

    @Test
    @DisplayName("a versão da tela precisa bater")
    void versao_desatualizada() throws SQLException {
        // Duas pessoas na mesma tarja: a segunda não pode ganhar em silêncio.
        cenario();
        var criado = solicitado(13);
        TenantContext.executar(tenant,
                () -> confirmar.executar(criado.id(), criado.versao(), false));

        // Volta ao SOLICITADO por fora: há o que confirmar de novo, mas a versão já avançou.
        forcarStatus(criado.id(), StatusAgendamento.SOLICITADO);

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> confirmar.executar(criado.id(), criado.versao(), false)));

        assertThat(codigoDe(erro)).isEqualTo("ER-INF-VERSAO_DESATUALIZADA");
    }

    @Test
    @DisplayName("cancelado não se confirma")
    void cancelado_nao_confirma() throws SQLException {
        // Confirmar um cancelado devolveria à agenda um horário que já foi oferecido a outra
        // pessoa.
        cenario();
        var criado = solicitado(13);
        forcarStatus(criado.id(), StatusAgendamento.CANCELADO);

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> confirmar.executar(criado.id(), criado.versao(), false)));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-TRANSICAO_INVALIDA");
    }

    @Test
    @DisplayName("concluído não volta a confirmado")
    void concluido_nao_confirma() throws SQLException {
        cenario();
        var criado = solicitado(13);
        forcarStatus(criado.id(), StatusAgendamento.CONCLUIDO);

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> confirmar.executar(criado.id(), criado.versao(), false)));

        assertThat(codigoDe(erro)).isEqualTo("ER-AGD-TRANSICAO_INVALIDA");
    }

    @Test
    @DisplayName("o evento diz quem confirmou")
    void evento_diz_quem_confirmou() {
        // Mandar "obrigado por confirmar" para quem não confirmou nada é pior do que não mandar.
        cenario();
        var criado = solicitado(13);

        TenantContext.executar(tenant,
                () -> confirmar.executar(criado.id(), criado.versao(), true));

        assertThat(eventos.stream(AgendamentoConfirmado.class).toList()).singleElement()
                .satisfies(evento -> {
                    assertThat(evento.confirmadoPelaCliente()).isTrue();
                    assertThat(evento.agendamentoId()).isEqualTo(criado.id());
                    assertThat(evento.estabelecimentoId()).isEqualTo(tenant);
                });
    }

    @Test
    @DisplayName("agendamento de outro salão não é encontrado")
    void isolamento_entre_saloes() {
        cenario();
        var doA = solicitado(13);
        UUID salaoB = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Outra",
                "outra-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        Throwable erro = catchThrowable(() -> TenantContext.executar(salaoB,
                () -> confirmar.executar(doA.id(), doA.versao(), false)));

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
