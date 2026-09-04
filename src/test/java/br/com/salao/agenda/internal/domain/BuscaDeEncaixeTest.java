package br.com.salao.agenda.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.agenda.api.CapacidadeDeRecurso;
import br.com.salao.agenda.api.Janela;
import br.com.salao.catalogo.api.BlocoDoServico;
import br.com.salao.catalogo.api.SequenciaDeBlocos;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Domínio puro: onde a receita cabe. */
class BuscaDeEncaixeTest {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate DIA = LocalDate.of(2026, 9, 15);
    private static final Duration QUINZE = Duration.ofMinutes(15);

    private Instant as(int hora, int minuto) {
        return DIA.atTime(LocalTime.of(hora, minuto)).atZone(SAO_PAULO).toInstant();
    }

    private Janela janela(int horaInicio, int horaFim) {
        return new Janela(as(horaInicio, 0), as(horaFim, 0));
    }

    private final Janela diaTodo = new Janela(
            DIA.atStartOfDay(SAO_PAULO).toInstant(),
            DIA.plusDays(1).atStartOfDay(SAO_PAULO).toInstant());

    /** Coloração: aplica 30, química age 40, lava e finaliza 30. */
    private SequenciaDeBlocos coloracao() {
        return new SequenciaDeBlocos(List.of(
                BlocoDoServico.ativo(30), BlocoDoServico.pausa(40), BlocoDoServico.ativo(30)));
    }

    private List<Instant> horarios(List<Janela> livres, SequenciaDeBlocos receita) {
        return BuscaDeEncaixe.horarios(livres, receita, Map.of(), QUINZE, diaTodo);
    }

    @Test
    @DisplayName("serviço simples cabe na jornada, de granularidade em granularidade")
    void servico_simples() {
        var horarios = horarios(List.of(janela(9, 11)), SequenciaDeBlocos.simples(45));

        assertThat(horarios).containsExactly(
                as(9, 0), as(9, 15), as(9, 30), as(9, 45),
                as(10, 0), as(10, 15));
        assertThat(horarios.getLast().plus(Duration.ofMinutes(45)))
                .as("o último termina exatamente no fim da jornada").isEqualTo(as(11, 0));
    }

    @Test
    @DisplayName("a PAUSA não exige o profissional — é o que faz a agenda render")
    void pausa_nao_exige_o_profissional() {
        // A profissional está livre das 9h às 10h e das 11h30 às 13h. A coloração começa às 9h30:
        // aplica até 10h (livre), a química age das 10h às 10h40 (a profissional está ocupada com
        // outra cliente, e tudo bem), e às 11h40... não, o bloco final precisa de janela livre.
        // Este teste prova o caso que funciona: a pausa cai inteira dentro do buraco.
        var livres = List.of(janela(9, 10), new Janela(as(10, 40), as(13, 0)));

        var horarios = BuscaDeEncaixe.horarios(livres, coloracao(), Map.of(), QUINZE, diaTodo);

        assertThat(horarios).as("às 9h30: aplica 9h30-10h, química 10h-10h40, finaliza 10h40-11h10")
                .contains(as(9, 30));
    }

    @Test
    @DisplayName("bloco ATIVO não atravessa o almoço")
    void ativo_nao_atravessa_buraco() {
        // Duas janelas somam três horas, mas um bloco de 45 minutos que comece 11h30 atravessa um
        // intervalo que não existe. Somar as janelas seria marcar em cima do almoço.
        var livres = List.of(janela(9, 12), janela(13, 18));

        var horarios = horarios(livres, SequenciaDeBlocos.simples(45));

        assertThat(horarios).contains(as(11, 15)).doesNotContain(as(11, 30), as(11, 45),
                as(12, 0), as(12, 30));
        assertThat(horarios).contains(as(13, 0));
    }

    @Test
    @DisplayName("recurso com duas unidades aceita duas simultâneas e recusa a terceira")
    void capacidade_do_recurso() {
        // É a razão de `recurso` ser contável: com dois lavatórios, duas lavagens ao mesmo tempo
        // ainda deixam o salão funcionando. A terceira é que não cabe.
        UUID lavatorio = UUID.randomUUID();
        var receita = new SequenciaDeBlocos(List.of(
                BlocoDoServico.ativo(30).exigindo(lavatorio)));
        var livres = List.of(janela(9, 12));

        var comUmaTomada = BuscaDeEncaixe.horarios(livres, receita,
                Map.of(lavatorio, new CapacidadeDeRecurso(2, List.of(janela(9, 10)))),
                QUINZE, diaTodo);
        var comDuasTomadas = BuscaDeEncaixe.horarios(livres, receita,
                Map.of(lavatorio, new CapacidadeDeRecurso(2, List.of(janela(9, 10),
                        janela(9, 10)))),
                QUINZE, diaTodo);

        assertThat(comUmaTomada).as("sobra um lavatório").contains(as(9, 0), as(9, 30));
        assertThat(comDuasTomadas).as("os dois tomados").doesNotContain(as(9, 0), as(9, 30));
        assertThat(comDuasTomadas).as("depois das 10h os dois voltam").contains(as(10, 0));
    }

    @Test
    @DisplayName("recurso exigido durante a PAUSA também bloqueia")
    void recurso_na_pausa_bloqueia() {
        // O caso que um modelo ingênuo erra: a profissional está livre durante a química, mas a
        // cliente segue na cadeira. Se a cadeira estiver tomada, o horário não existe.
        UUID cadeira = UUID.randomUUID();
        var receita = new SequenciaDeBlocos(List.of(
                BlocoDoServico.ativo(30),
                BlocoDoServico.pausa(40).exigindo(cadeira),
                BlocoDoServico.ativo(30)));
        var livres = List.of(janela(9, 13));

        var horarios = BuscaDeEncaixe.horarios(livres, receita,
                Map.of(cadeira, new CapacidadeDeRecurso(1, List.of(janela(9, 11)))),
                QUINZE, diaTodo);

        assertThat(horarios).as("a cadeira ocupada até 11h impede toda pausa antes disso")
                .doesNotContain(as(9, 0), as(9, 30), as(10, 0));
        assertThat(horarios).contains(as(11, 0));
    }

    @Test
    @DisplayName("recurso exigido e desconhecido recusa o horário")
    void recurso_desconhecido_recusa() {
        // Devolver o horário e descobrir na gravação seria oferecer à cliente algo que não existe.
        var receita = new SequenciaDeBlocos(List.of(
                BlocoDoServico.ativo(30).exigindo(UUID.randomUUID())));

        assertThat(BuscaDeEncaixe.horarios(List.of(janela(9, 12)), receita, Map.of(), QUINZE,
                diaTodo)).isEmpty();
    }

    @Test
    @DisplayName("sem jornada, não há horário")
    void sem_jornada() {
        assertThat(horarios(List.of(), SequenciaDeBlocos.simples(45))).isEmpty();
    }

    @Test
    @DisplayName("serviço maior que a jornada não cabe em lugar nenhum")
    void servico_maior_que_a_jornada() {
        assertThat(horarios(List.of(janela(9, 10)), SequenciaDeBlocos.simples(90))).isEmpty();
    }

    @Test
    @DisplayName("a granularidade do salão define a grade")
    void granularidade_manda() {
        var livres = List.of(janela(9, 10));
        var receita = SequenciaDeBlocos.simples(30);

        var deTrintaEmTrinta = BuscaDeEncaixe.horarios(livres, receita, Map.of(),
                Duration.ofMinutes(30), diaTodo);
        var deDezEmDez = BuscaDeEncaixe.horarios(livres, receita, Map.of(),
                Duration.ofMinutes(10), diaTodo);

        assertThat(deTrintaEmTrinta).containsExactly(as(9, 0), as(9, 30));
        assertThat(deDezEmDez).containsExactly(as(9, 0), as(9, 10), as(9, 20), as(9, 30));
    }

    @Test
    @DisplayName("dois atendimentos encostados não conflitam")
    void encostar_nao_e_conflito() {
        // Com o fim fechado, todo horário cheio de agenda seria conflito: a recepção aprenderia a
        // marcar às 10h01 e o dia perderia um minuto por cliente.
        var livres = SubtracaoDeJanelas.subtrair(List.of(janela(9, 12)),
                List.of(new Janela(as(9, 0), as(10, 0))));

        var horarios = BuscaDeEncaixe.horarios(livres, SequenciaDeBlocos.simples(60), Map.of(),
                QUINZE, diaTodo);

        assertThat(horarios).as("às 10h em ponto, encostado no que terminou").contains(as(10, 0));
    }
}
