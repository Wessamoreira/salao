package br.com.salao.equipe.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.salao.equipe.api.IntervaloDeTrabalho;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Domínio puro, e a parte com mais casos de borda da rotina.
 *
 * <p>Um bloqueio pode cortar o começo, o fim, o meio (partindo o intervalo em dois), o intervalo
 * inteiro, ou nada. Cada um desses é um jeito de a agenda mostrar horário errado.
 */
class SubtracaoDeIntervalosTest {

    private IntervaloDeTrabalho das(String inicio, String fim) {
        return new IntervaloDeTrabalho(LocalTime.parse(inicio), LocalTime.parse(fim));
    }

    private List<IntervaloDeTrabalho> tirar(List<IntervaloDeTrabalho> base,
                                            IntervaloDeTrabalho... removidos) {
        return SubtracaoDeIntervalos.subtrair(base, List.of(removidos));
    }

    @Test
    @DisplayName("corte no meio PARTE o intervalo em dois")
    void corte_no_meio_parte_em_dois() {
        // O curso das 14h às 16h no meio do expediente é o caso que mais aparece — e o que um
        // `if` simples resolveria errado, devolvendo um intervalo só.
        var sobra = tirar(List.of(das("09:00", "19:00")), das("14:00", "16:00"));

        assertThat(sobra).containsExactly(das("09:00", "14:00"), das("16:00", "19:00"));
    }

    @Test
    @DisplayName("corte no começo encurta o início")
    void corte_no_comeco() {
        var sobra = tirar(List.of(das("09:00", "19:00")), das("08:00", "11:00"));

        assertThat(sobra).containsExactly(das("11:00", "19:00"));
    }

    @Test
    @DisplayName("corte no fim encurta o término")
    void corte_no_fim() {
        var sobra = tirar(List.of(das("09:00", "19:00")), das("17:00", "22:00"));

        assertThat(sobra).containsExactly(das("09:00", "17:00"));
    }

    @Test
    @DisplayName("bloqueio que cobre tudo não deixa nada")
    void cobre_tudo() {
        // Férias: o dia inteiro sai, e a agenda não pode oferecer nem um horário.
        assertThat(tirar(List.of(das("09:00", "19:00")), das("00:00", "23:59"))).isEmpty();
    }

    @Test
    @DisplayName("bloqueio fora do expediente não muda nada")
    void fora_do_expediente() {
        var sobra = tirar(List.of(das("09:00", "19:00")), das("20:00", "22:00"));

        assertThat(sobra).containsExactly(das("09:00", "19:00"));
    }

    @Test
    @DisplayName("bloqueio encostado não corta: 19h fecha o expediente")
    void encostado_nao_corta() {
        assertThat(tirar(List.of(das("09:00", "19:00")), das("19:00", "22:00")))
                .containsExactly(das("09:00", "19:00"));
        assertThat(tirar(List.of(das("09:00", "19:00")), das("07:00", "09:00")))
                .containsExactly(das("09:00", "19:00"));
    }

    @Test
    @DisplayName("com almoço, o bloqueio atinge só o intervalo que cruza")
    void jornada_com_almoco() {
        // A jornada real do salão são dois intervalos, e o curso da tarde só toca um deles.
        var jornada = List.of(das("09:00", "13:00"), das("14:00", "19:00"));

        var sobra = tirar(jornada, das("15:00", "17:00"));

        assertThat(sobra).containsExactly(
                das("09:00", "13:00"), das("14:00", "15:00"), das("17:00", "19:00"));
    }

    @Test
    @DisplayName("bloqueio que engole o almoço junto tira os dois pedaços")
    void bloqueio_atravessa_o_almoco() {
        var jornada = List.of(das("09:00", "13:00"), das("14:00", "19:00"));

        var sobra = tirar(jornada, das("12:00", "16:00"));

        assertThat(sobra).containsExactly(das("09:00", "12:00"), das("16:00", "19:00"));
    }

    @Test
    @DisplayName("bloqueios sobrepostos entre si dão o mesmo resultado")
    void bloqueios_sobrepostos() {
        // Férias que englobam um feriado é banal, e é por isso que o banco não proíbe (V19).
        // A subtração aplicada em sequência precisa aguentar isso.
        var jornada = List.of(das("09:00", "19:00"));

        var sobra = tirar(jornada, das("13:00", "16:00"), das("14:00", "18:00"));

        assertThat(sobra).containsExactly(das("09:00", "13:00"), das("18:00", "19:00"));
    }

    @Test
    @DisplayName("sem bloqueio, a jornada sai inteira e em ordem")
    void sem_bloqueio() {
        var jornada = List.of(das("14:00", "19:00"), das("09:00", "13:00"));

        assertThat(tirar(jornada)).containsExactly(das("09:00", "13:00"), das("14:00", "19:00"));
    }

    @Test
    @DisplayName("jornada vazia continua vazia")
    void jornada_vazia() {
        assertThat(tirar(List.of(), das("09:00", "19:00"))).isEmpty();
    }

    @Test
    @DisplayName("o resultado não pode ser alterado por quem o recebeu")
    void resultado_e_imutavel() {
        var sobra = tirar(List.of(das("09:00", "19:00")), das("14:00", "16:00"));

        // Testar o NOME da classe seria testar a implementação; o que importa é o efeito.
        assertThatThrownBy(() -> sobra.add(das("20:00", "21:00")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
