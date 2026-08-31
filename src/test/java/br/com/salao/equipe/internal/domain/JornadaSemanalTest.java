package br.com.salao.equipe.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.salao.equipe.api.IntervaloDeTrabalho;
import br.com.salao.equipe.api.JornadaSemanal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Domínio puro: a jornada de uma semana comum. */
class JornadaSemanalTest {

    private IntervaloDeTrabalho das(String inicio, String fim) {
        return new IntervaloDeTrabalho(LocalTime.parse(inicio), LocalTime.parse(fim));
    }

    /** Terça a sábado, com almoço — a jornada típica de um salão. */
    private JornadaSemanal comAlmoco() {
        var manhaETarde = List.of(das("09:00", "13:00"), das("14:00", "19:00"));
        return new JornadaSemanal(Map.of(
                DayOfWeek.TUESDAY, manhaETarde,
                DayOfWeek.SATURDAY, List.of(das("09:00", "17:00"))));
    }

    @Test
    @DisplayName("o almoço é a AUSÊNCIA de intervalo, não um campo")
    void almoco_e_ausencia_de_intervalo() {
        // O modelo ingênuo — inicio, fim, almoco_inicio, almoco_fim — quebra na primeira
        // profissional com três blocos ou dois intervalos. Aqui isso não é caso especial.
        var jornada = comAlmoco();

        assertThat(jornada.trabalhaEm(DayOfWeek.TUESDAY, LocalTime.of(10, 0))).isTrue();
        assertThat(jornada.trabalhaEm(DayOfWeek.TUESDAY, LocalTime.of(13, 30)))
                .as("no almoço não atende").isFalse();
        assertThat(jornada.trabalhaEm(DayOfWeek.TUESDAY, LocalTime.of(15, 0))).isTrue();
    }

    @Test
    @DisplayName("três blocos no mesmo dia cabem sem inventar campo")
    void tres_blocos_cabem() {
        var jornada = new JornadaSemanal(Map.of(DayOfWeek.WEDNESDAY,
                List.of(das("08:00", "11:00"), das("13:00", "16:00"), das("18:00", "21:00"))));

        assertThat(jornada.em(DayOfWeek.WEDNESDAY)).hasSize(3);
        assertThat(jornada.trabalhaEm(DayOfWeek.WEDNESDAY, LocalTime.of(19, 0))).isTrue();
        assertThat(jornada.trabalhaEm(DayOfWeek.WEDNESDAY, LocalTime.of(17, 0))).isFalse();
    }

    @Test
    @DisplayName("dia sem jornada não atende — e vazio não é 'atende sempre'")
    void dia_sem_jornada_nao_atende() {
        // O padrão precisa ser o que NÃO marca horário para quem não trabalha.
        var jornada = comAlmoco();

        assertThat(jornada.em(DayOfWeek.MONDAY)).isEmpty();
        assertThat(jornada.trabalhaEm(DayOfWeek.MONDAY, LocalTime.of(10, 0))).isFalse();
        assertThat(JornadaSemanal.vazia().trabalhaEm(DayOfWeek.TUESDAY, LocalTime.of(10, 0)))
                .isFalse();
    }

    @Test
    @DisplayName("o fim é exclusivo: às 19h ele já foi embora")
    void fim_e_exclusivo() {
        // Se 19h contasse como dentro, a última cliente do dia começaria no horário de fechar.
        var jornada = comAlmoco();

        assertThat(jornada.trabalhaEm(DayOfWeek.TUESDAY, LocalTime.of(18, 59))).isTrue();
        assertThat(jornada.trabalhaEm(DayOfWeek.TUESDAY, LocalTime.of(19, 0))).isFalse();
        assertThat(jornada.trabalhaEm(DayOfWeek.TUESDAY, LocalTime.of(9, 0)))
                .as("o início é inclusivo").isTrue();
    }

    @Test
    @DisplayName("soma os minutos da semana — base do cálculo de ocupação")
    void soma_os_minutos() {
        // Terça: 4h + 5h = 9h. Sábado: 8h. Total 17h = 1020 min.
        assertThat(comAlmoco().minutosNaSemana()).isEqualTo(1020);
    }

    @Test
    @DisplayName("intervalo invertido é recusado na construção")
    void intervalo_invertido() {
        assertThatThrownBy(() -> das("19:00", "09:00"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> das("09:00", "09:00"))
                .as("intervalo de duração zero não é jornada")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("sobreposição é reconhecida pelo próprio intervalo")
    void reconhece_sobreposicao() {
        assertThat(das("09:00", "13:00").sobrepoe(das("12:00", "18:00"))).isTrue();
        assertThat(das("09:00", "13:00").sobrepoe(das("13:00", "18:00")))
                .as("encostar não é sobrepor: 13h fecha um e abre o outro").isFalse();
        assertThat(das("09:00", "19:00").sobrepoe(das("13:00", "14:00")))
                .as("um dentro do outro também é sobreposição").isTrue();
    }

    @Test
    @DisplayName("a jornada devolvida não pode ser alterada por quem a recebeu")
    void jornada_e_imutavel() {
        var jornada = comAlmoco();

        assertThatThrownBy(() -> jornada.em(DayOfWeek.TUESDAY).add(das("20:00", "22:00")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
