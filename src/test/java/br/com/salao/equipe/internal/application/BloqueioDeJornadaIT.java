package br.com.salao.equipe.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.equipe.api.IntervaloDeTrabalho;
import br.com.salao.equipe.api.MotivoDoBloqueio;
import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.equipe.api.TipoDeVinculo;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-EQP-003 — exceções de jornada. */
@WithMockUser(authorities = Permissao.USUARIO_GERENCIAR)
class BloqueioDeJornadaIT extends AbstractPostgresIT {

    /** Uma terça-feira, para casar com a jornada de terça usada nos testes. */
    private static final LocalDate TERCA = LocalDate.of(2026, 9, 1);

    @Autowired
    private ProvisionarEstabelecimentoUseCase provisionar;

    @Autowired
    private CadastrarProfissionalUseCase cadastrar;

    @Autowired
    private DefinirJornadaUseCase definirJornada;

    @Autowired
    private RegistrarBloqueioUseCase registrarBloqueio;

    @Autowired
    private RemoverBloqueioUseCase removerBloqueio;

    @Autowired
    private ProfissionalApi equipe;

    private UUID salao() {
        return provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Ana",
                "ana-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
    }

    private UUID profissionalCom9as19(UUID tenant, String documento) {
        UUID id = TenantContext.obter(tenant, () -> cadastrar.executar(
                new DadosDoProfissional("Marilda", null, documento, TipoDeVinculo.PARCEIRO,
                        null, null, new BigDecimal("45"), null, null, null))).id();
        TenantContext.executar(tenant, () -> definirJornada.executar(id,
                Map.of(DayOfWeek.TUESDAY, List.of(
                        new PeriodoBruto(LocalTime.parse("09:00"), LocalTime.parse("13:00")),
                        new PeriodoBruto(LocalTime.parse("14:00"), LocalTime.parse("19:00"))))));
        return id;
    }

    private IntervaloDeTrabalho das(String inicio, String fim) {
        return new IntervaloDeTrabalho(LocalTime.parse(inicio), LocalTime.parse(fim));
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    @Test
    @DisplayName("sem bloqueio, o dia devolve a jornada da terça")
    void sem_bloqueio_devolve_a_jornada() {
        UUID tenant = salao();
        UUID profissional = profissionalCom9as19(tenant, "11222333000181");

        assertThat(TenantContext.obter(tenant, () -> equipe.intervalosEm(profissional, TERCA)))
                .containsExactly(das("09:00", "13:00"), das("14:00", "19:00"));
    }

    @Test
    @DisplayName("curso da tarde PARTE o expediente, e a agenda enxerga os dois pedaços")
    void curso_parte_o_expediente() {
        // É o caso que mais aparece, e o que um modelo de "dia livre ou dia ocupado" erraria:
        // a pessoa atende de manhã e depois das 16h.
        UUID tenant = salao();
        UUID profissional = profissionalCom9as19(tenant, "11222333000181");

        TenantContext.executar(tenant, () -> registrarBloqueio.executar(profissional,
                MotivoDoBloqueio.CURSO, "curso de coloração", TERCA, TERCA,
                LocalTime.parse("15:00"), LocalTime.parse("17:00")));

        assertThat(TenantContext.obter(tenant, () -> equipe.intervalosEm(profissional, TERCA)))
                .containsExactly(das("09:00", "13:00"), das("14:00", "15:00"),
                        das("17:00", "19:00"));
    }

    @Test
    @DisplayName("férias tiram o dia inteiro, em todos os dias do período")
    void ferias_tiram_o_dia_inteiro() {
        UUID tenant = salao();
        UUID profissional = profissionalCom9as19(tenant, "11222333000181");

        TenantContext.executar(tenant, () -> registrarBloqueio.executar(profissional,
                MotivoDoBloqueio.FERIAS, null, TERCA.minusDays(3), TERCA.plusDays(10),
                null, null));

        assertThat(TenantContext.obter(tenant, () -> equipe.intervalosEm(profissional, TERCA)))
                .isEmpty();
        assertThat(TenantContext.obter(tenant,
                () -> equipe.intervalosEm(profissional, TERCA.plusDays(7))))
                .as("a terça seguinte também está dentro das férias").isEmpty();
    }

    @Test
    @DisplayName("feriado sem profissional fecha o salão para TODO MUNDO")
    void feriado_fecha_o_salao() {
        // Modelar feriado por profissional obrigaria a criar uma linha para cada um — e a
        // esquecer de criar para quem for contratado depois, que veria o salão aberto no Natal.
        UUID tenant = salao();
        UUID uma = profissionalCom9as19(tenant, "11222333000181");
        UUID outra = profissionalCom9as19(tenant, "11111111000191");

        TenantContext.executar(tenant, () -> registrarBloqueio.executar(null,
                MotivoDoBloqueio.FERIADO, "Independência", TERCA, TERCA, null, null));

        assertThat(TenantContext.obter(tenant, () -> equipe.intervalosEm(uma, TERCA))).isEmpty();
        assertThat(TenantContext.obter(tenant, () -> equipe.intervalosEm(outra, TERCA))).isEmpty();
    }

    @Test
    @DisplayName("o bloqueio de uma pessoa não afeta a outra")
    void bloqueio_e_de_quem_e() {
        UUID tenant = salao();
        UUID uma = profissionalCom9as19(tenant, "11222333000181");
        UUID outra = profissionalCom9as19(tenant, "11111111000191");

        TenantContext.executar(tenant, () -> registrarBloqueio.executar(uma,
                MotivoDoBloqueio.ATESTADO, null, TERCA, TERCA, null, null));

        assertThat(TenantContext.obter(tenant, () -> equipe.intervalosEm(uma, TERCA))).isEmpty();
        assertThat(TenantContext.obter(tenant, () -> equipe.intervalosEm(outra, TERCA)))
                .hasSize(2);
    }

    @Test
    @DisplayName("bloqueios sobrepostos são aceitos — férias que englobam um feriado é banal")
    void bloqueios_sobrepostos_sao_normais() {
        // Ao contrário da jornada, proibir aqui obrigaria a recepção a apagar o feriado para
        // lançar as férias.
        UUID tenant = salao();
        UUID profissional = profissionalCom9as19(tenant, "11222333000181");

        TenantContext.executar(tenant, () -> registrarBloqueio.executar(profissional,
                MotivoDoBloqueio.FERIAS, null, TERCA.minusDays(2), TERCA.plusDays(2), null, null));
        TenantContext.executar(tenant, () -> registrarBloqueio.executar(null,
                MotivoDoBloqueio.FERIADO, null, TERCA, TERCA, null, null));

        assertThat(TenantContext.obter(tenant, () -> equipe.intervalosEm(profissional, TERCA)))
                .isEmpty();
        assertThat(TenantContext.obter(tenant,
                () -> equipe.bloqueiosEntre(profissional, TERCA, TERCA))).hasSize(2);
    }

    @Test
    @DisplayName("dia sem jornada continua sem atendimento, com ou sem bloqueio")
    void dia_sem_jornada() {
        UUID tenant = salao();
        UUID profissional = profissionalCom9as19(tenant, "11222333000181");

        assertThat(TenantContext.obter(tenant,
                () -> equipe.intervalosEm(profissional, TERCA.plusDays(1))))
                .as("quarta não está na jornada").isEmpty();
    }

    @Test
    @DisplayName("remover o bloqueio devolve o horário")
    void remover_devolve_o_horario() {
        // Bloqueio lançado por engano precisa sumir de verdade, senão a agenda fica com um buraco
        // que ninguém explica.
        UUID tenant = salao();
        UUID profissional = profissionalCom9as19(tenant, "11222333000181");
        var bloqueio = TenantContext.obter(tenant, () -> registrarBloqueio.executar(profissional,
                MotivoDoBloqueio.FOLGA, null, TERCA, TERCA, null, null));

        TenantContext.executar(tenant, () -> removerBloqueio.executar(bloqueio.id()));

        assertThat(TenantContext.obter(tenant, () -> equipe.intervalosEm(profissional, TERCA)))
                .hasSize(2);
    }

    @Test
    @DisplayName("remover bloqueio inexistente é 404")
    void remover_inexistente() {
        UUID tenant = salao();

        var erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> removerBloqueio.executar(UUID.randomUUID())));

        assertThat(codigoDe(erro)).isEqualTo("ER-EQP-BLOQUEIO_NAO_ENCONTRADO");
    }

    @Test
    @DisplayName("bloqueio por hora em mais de um dia é recusado")
    void bloqueio_por_hora_em_um_dia_so() {
        // "Das 14h às 18h por uma quinzena" ninguém pede, e aceitar obrigaria toda consulta de
        // disponibilidade a tratar um caso que não existe.
        UUID tenant = salao();
        UUID profissional = profissionalCom9as19(tenant, "11222333000181");

        var erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> registrarBloqueio.executar(profissional, MotivoDoBloqueio.CURSO, null,
                        TERCA, TERCA.plusDays(5), LocalTime.parse("14:00"),
                        LocalTime.parse("18:00"))));

        assertThat(codigoDe(erro)).isEqualTo("ER-EQP-DADOS_INVALIDOS");
    }

    @Test
    @DisplayName("uma hora só, sem a outra, é recusado")
    void horas_vao_juntas() {
        UUID tenant = salao();
        UUID profissional = profissionalCom9as19(tenant, "11222333000181");

        var erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> registrarBloqueio.executar(profissional, MotivoDoBloqueio.CURSO, null,
                        TERCA, TERCA, LocalTime.parse("14:00"), null)));

        assertThat(codigoDe(erro)).isEqualTo("ER-EQP-DADOS_INVALIDOS");
    }

    @Test
    @DisplayName("período invertido é recusado")
    void periodo_invertido() {
        UUID tenant = salao();

        var erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> registrarBloqueio.executar(null, MotivoDoBloqueio.FERIADO, null,
                        TERCA, TERCA.minusDays(1), null, null)));

        assertThat(codigoDe(erro)).isEqualTo("ER-EQP-DADOS_INVALIDOS");
    }

    @Test
    @DisplayName("a observação de atestado não vai para a trilha — é dado de saúde")
    void observacao_nao_vai_para_a_trilha() throws Exception {
        // "Cirurgia no joelho" digitado no campo livre não pode acabar numa tabela append-only,
        // fora do alcance de qualquer expurgo (mesma razão de RN-CLI-007).
        UUID tenant = salao();
        UUID profissional = profissionalCom9as19(tenant, "11222333000181");

        TenantContext.executar(tenant, () -> registrarBloqueio.executar(profissional,
                MotivoDoBloqueio.ATESTADO, "cirurgia no joelho", TERCA, TERCA, null, null));

        try (var st = comoOwner().createStatement();
             var rs = st.executeQuery("""
                     select depois::text from auditoria where entidade = 'bloqueio_de_jornada'
                     """)) {
            assertThat(rs.next()).isTrue();
            String trilha = rs.getString(1);
            assertThat(trilha).contains("ATESTADO");
            assertThat(trilha).doesNotContain("joelho");
        }
    }

    @Test
    @DisplayName("bloqueio de outro salão não afeta este")
    void isolamento_entre_tenants() {
        UUID salaoA = salao();
        UUID salaoB = salao();
        UUID daA = profissionalCom9as19(salaoA, "11222333000181");
        UUID daB = profissionalCom9as19(salaoB, "11222333000181");

        TenantContext.executar(salaoA, () -> registrarBloqueio.executar(null,
                MotivoDoBloqueio.FERIADO, null, TERCA, TERCA, null, null));

        assertThat(TenantContext.obter(salaoA, () -> equipe.intervalosEm(daA, TERCA))).isEmpty();
        assertThat(TenantContext.obter(salaoB, () -> equipe.intervalosEm(daB, TERCA)))
                .as("o feriado de um salão não fecha o outro").hasSize(2);
    }

    @Test
    @DisplayName("sem usuario:manage, registrar bloqueio é recusado no caso de uso")
    @WithMockUser(authorities = Permissao.AGENDA_LER_TODAS)
    void sem_permissao_nao_bloqueia() {
        UUID tenant = salao();

        assertThatThrownBy(() -> TenantContext.executar(tenant,
                () -> registrarBloqueio.executar(null, MotivoDoBloqueio.FERIADO, null,
                        TERCA, TERCA, null, null)))
                .isInstanceOf(AccessDeniedException.class);
    }
}
