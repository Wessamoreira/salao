package br.com.salao.equipe.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.equipe.api.IntervaloDeTrabalho;
import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.equipe.api.TipoDeVinculo;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-EQP-002 — jornada semanal. */
@WithMockUser(authorities = Permissao.USUARIO_GERENCIAR)
class JornadaIT extends AbstractPostgresIT {

    private static final String CNPJ = "11222333000181";

    @Autowired
    private ProvisionarEstabelecimentoUseCase provisionar;

    @Autowired
    private CadastrarProfissionalUseCase cadastrar;

    @Autowired
    private DefinirJornadaUseCase definirJornada;

    @Autowired
    private ProfissionalApi equipe;

    private UUID salao() {
        return provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Ana",
                "ana-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
    }

    private UUID profissionalEm(UUID tenant) {
        return TenantContext.obter(tenant, () -> cadastrar.executar(
                new DadosDoProfissional("Marilda", "Nice", CNPJ, TipoDeVinculo.PARCEIRO,
                        null, null, new BigDecimal("45"), null, null, null))).id();
    }

    /** O que a borda manda: horários crus, ainda não validados. */
    private PeriodoBruto periodo(String inicio, String fim) {
        return new PeriodoBruto(LocalTime.parse(inicio), LocalTime.parse(fim));
    }

    private IntervaloDeTrabalho das(String inicio, String fim) {
        return new IntervaloDeTrabalho(LocalTime.parse(inicio), LocalTime.parse(fim));
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    @Test
    @DisplayName("grava a semana com almoço e devolve na ordem do dia")
    void grava_semana_com_almoco() {
        UUID tenant = salao();
        UUID profissional = profissionalEm(tenant);

        var salva = TenantContext.obter(tenant, () -> definirJornada.executar(profissional, Map.of(
                DayOfWeek.TUESDAY, List.of(periodo("09:00", "13:00"), periodo("14:00", "19:00")),
                DayOfWeek.SATURDAY, List.of(periodo("09:00", "17:00")))));

        assertThat(salva.em(DayOfWeek.TUESDAY))
                .containsExactly(das("09:00", "13:00"), das("14:00", "19:00"));
        assertThat(salva.minutosNaSemana()).isEqualTo(1020);
        assertThat(salva.trabalhaEm(DayOfWeek.TUESDAY, LocalTime.of(13, 30)))
                .as("no almoço não atende").isFalse();
    }

    @Test
    @DisplayName("profissional sem jornada devolve vazia — que significa 'não atende'")
    void sem_jornada_e_vazia() {
        // O padrão precisa ser o que NÃO marca horário para quem não trabalha.
        UUID tenant = salao();
        UUID profissional = profissionalEm(tenant);

        var jornada = TenantContext.obter(tenant, () -> equipe.jornadaDe(profissional));

        assertThat(jornada.estaVazia()).isTrue();
        assertThat(jornada.trabalhaEm(DayOfWeek.TUESDAY, LocalTime.of(10, 0))).isFalse();
    }

    @Test
    @DisplayName("redefinir substitui a semana inteira, sem sobrar intervalo antigo")
    void redefinir_substitui() {
        UUID tenant = salao();
        UUID profissional = profissionalEm(tenant);
        TenantContext.executar(tenant, () -> definirJornada.executar(profissional, Map.of(
                DayOfWeek.TUESDAY, List.of(periodo("09:00", "19:00")),
                DayOfWeek.THURSDAY, List.of(periodo("09:00", "19:00")))));

        var nova = TenantContext.obter(tenant, () -> definirJornada.executar(profissional,
                Map.of(DayOfWeek.TUESDAY, List.of(periodo("10:00", "16:00")))));

        assertThat(nova.em(DayOfWeek.TUESDAY)).containsExactly(das("10:00", "16:00"));
        assertThat(nova.em(DayOfWeek.THURSDAY)).as("a quinta saiu de verdade").isEmpty();
    }

    @Test
    @DisplayName("limpar a jornada é mandar uma semana vazia")
    void limpar_jornada() {
        UUID tenant = salao();
        UUID profissional = profissionalEm(tenant);
        TenantContext.executar(tenant, () -> definirJornada.executar(profissional,
                Map.of(DayOfWeek.TUESDAY, List.of(periodo("09:00", "19:00")))));

        var vazia = TenantContext.obter(tenant,
                () -> definirJornada.executar(profissional, Map.of()));

        assertThat(vazia.estaVazia()).isTrue();
    }

    @Test
    @DisplayName("sobreposição é recusada, e a mensagem diz quais horários colidem")
    void recusa_sobreposicao() {
        // O erro de constraint diria apenas que algo colidiu; a tela precisa saber o quê.
        UUID tenant = salao();
        UUID profissional = profissionalEm(tenant);

        var erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> definirJornada.executar(profissional, Map.of(DayOfWeek.TUESDAY,
                        List.of(periodo("09:00", "13:00"), periodo("12:00", "18:00"))))));

        assertThat(codigoDe(erro)).isEqualTo("ER-EQP-JORNADA_SOBREPOSTA");
        assertThat(erro).hasMessageContaining("09:00").hasMessageContaining("12:00");
    }

    @Test
    @DisplayName("intervalos encostados são aceitos: 13h fecha um e abre o outro")
    void encostar_nao_e_sobrepor() {
        UUID tenant = salao();
        UUID profissional = profissionalEm(tenant);

        var salva = TenantContext.obter(tenant, () -> definirJornada.executar(profissional,
                Map.of(DayOfWeek.TUESDAY,
                        List.of(periodo("09:00", "13:00"), periodo("13:00", "19:00")))));

        assertThat(salva.em(DayOfWeek.TUESDAY)).hasSize(2);
    }

    @Test
    @DisplayName("o BANCO impede sobreposição, não só o caso de uso")
    void banco_impede_sobreposicao() throws SQLException {
        // Duas instâncias salvando ao mesmo tempo passariam por qualquer verificação em Java:
        // as duas leem, nenhuma vê conflito, as duas gravam. O EXCLUDE não tem esse buraco.
        UUID tenant = salao();
        UUID profissional = profissionalEm(tenant);
        TenantContext.executar(tenant, () -> definirJornada.executar(profissional,
                Map.of(DayOfWeek.TUESDAY, List.of(periodo("09:00", "13:00")))));

        try (var ps = comoOwner().prepareStatement("""
                insert into jornada_profissional
                    (estabelecimento_id, profissional_id, dia_semana, inicio, fim)
                values (?, ?, 2, '12:00', '18:00')
                """)) {
            ps.setObject(1, tenant);
            ps.setObject(2, profissional);
            assertThatThrownBy(ps::executeUpdate)
                    .hasMessageContaining("jornada_sem_sobreposicao");
        }
    }

    @Test
    @DisplayName("o mesmo horário em dias diferentes não colide")
    void dias_diferentes_nao_colidem() {
        UUID tenant = salao();
        UUID profissional = profissionalEm(tenant);

        var salva = TenantContext.obter(tenant, () -> definirJornada.executar(profissional, Map.of(
                DayOfWeek.TUESDAY, List.of(periodo("09:00", "19:00")),
                DayOfWeek.WEDNESDAY, List.of(periodo("09:00", "19:00")))));

        assertThat(salva.porDia()).hasSize(2);
    }

    @Test
    @DisplayName("dois profissionais podem ter o mesmo horário")
    void profissionais_diferentes_nao_colidem() {
        UUID tenant = salao();
        UUID uma = profissionalEm(tenant);
        UUID outra = TenantContext.obter(tenant, () -> cadastrar.executar(
                new DadosDoProfissional("Joana", null, "11111111000191", TipoDeVinculo.PARCEIRO,
                        null, null, BigDecimal.ZERO, null, null, null))).id();
        var terca = Map.of(DayOfWeek.TUESDAY, List.of(periodo("09:00", "19:00")));

        TenantContext.executar(tenant, () -> definirJornada.executar(uma, terca));
        TenantContext.executar(tenant, () -> definirJornada.executar(outra, terca));

        assertThat(TenantContext.obter(tenant, () -> equipe.jornadaDe(outra)).minutosNaSemana())
                .isEqualTo(600);
    }

    @Test
    @DisplayName("intervalo invertido vindo de fora é 422, não 500")
    void intervalo_invertido_e_erro_de_dominio() {
        // O construtor do domínio levanta IllegalArgumentException, e sem tradução ela cairia no
        // tratador genérico — 500 dizendo "algo quebrou aqui dentro" sobre um horário que a
        // pessoa digitou trocado. É a quarta vez que esta família de defeito aparece.
        UUID tenant = salao();
        UUID profissional = profissionalEm(tenant);

        var erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> definirJornada.executar(profissional,
                        Map.of(DayOfWeek.TUESDAY, List.of(periodo("19:00", "09:00"))))));

        assertThat(codigoDe(erro)).isEqualTo("ER-EQP-DADOS_INVALIDOS");
    }

    @Test
    @DisplayName("jornada de profissional inexistente é recusada")
    void profissional_inexistente() {
        UUID tenant = salao();

        var erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> definirJornada.executar(UUID.randomUUID(), Map.of())));

        assertThat(codigoDe(erro)).isEqualTo("ER-EQP-PROFISSIONAL_NAO_ENCONTRADO");
    }

    @Test
    @DisplayName("a jornada não atravessa salões")
    void isolamento_entre_tenants() {
        UUID salaoA = salao();
        UUID salaoB = salao();
        UUID daA = profissionalEm(salaoA);
        TenantContext.executar(salaoA, () -> definirJornada.executar(daA,
                Map.of(DayOfWeek.TUESDAY, List.of(periodo("09:00", "19:00")))));

        assertThat(TenantContext.obter(salaoB, () -> equipe.jornadaDe(daA)).estaVazia()).isTrue();
    }

    @Test
    @DisplayName("sem usuario:manage, definir jornada é recusado no caso de uso")
    @WithMockUser(authorities = Permissao.AGENDA_LER_TODAS)
    void sem_permissao_nao_define() {
        UUID tenant = salao();

        assertThatThrownBy(() -> TenantContext.executar(tenant,
                () -> definirJornada.executar(UUID.randomUUID(), Map.of())))
                .isInstanceOf(AccessDeniedException.class);
    }
}
