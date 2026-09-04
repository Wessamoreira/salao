package br.com.salao.agenda.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.salao.agenda.api.CapacidadeDeRecurso;
import br.com.salao.agenda.api.Janela;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Domínio puro: janela, subtração e capacidade. */
class JanelaESubtracaoTest {

    private Instant t(int minuto) {
        return Instant.parse("2026-09-15T09:00:00Z").plusSeconds(minuto * 60L);
    }

    private Janela janela(int de, int ate) {
        return new Janela(t(de), t(ate));
    }

    @Test
    @DisplayName("encostar não é cruzar")
    void encostar_nao_e_cruzar() {
        // O fim é aberto, como o tstzrange das constraints. Se domínio e banco discordassem sobre
        // isto, a aplicação ofereceria horários que o banco recusaria na hora de gravar.
        assertThat(janela(0, 60).cruzaCom(janela(60, 120))).isFalse();
        assertThat(janela(0, 60).cruzaCom(janela(59, 120))).isTrue();
    }

    @Test
    @DisplayName("janela vazia ou invertida é recusada")
    void janela_precisa_ter_duracao() {
        assertThatThrownBy(() -> janela(60, 60)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> janela(60, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("um trecho tomado no meio parte a janela em duas")
    void tomada_no_meio_parte_em_duas() {
        // O caso que importa: livre das 9h às 18h com uma cliente das 13h às 14h são duas janelas,
        // não uma de nove horas.
        var sobrou = SubtracaoDeJanelas.subtrair(List.of(janela(0, 540)),
                List.of(janela(240, 300)));

        assertThat(sobrou).containsExactly(janela(0, 240), janela(300, 540));
    }

    @Test
    @DisplayName("tomadas encostadas nas pontas encolhem sem partir")
    void tomadas_nas_pontas() {
        assertThat(SubtracaoDeJanelas.subtrair(List.of(janela(0, 120)), List.of(janela(0, 30))))
                .containsExactly(janela(30, 120));
        assertThat(SubtracaoDeJanelas.subtrair(List.of(janela(0, 120)), List.of(janela(90, 120))))
                .containsExactly(janela(0, 90));
    }

    @Test
    @DisplayName("tomadas sobrepostas entre si não geram janela negativa")
    void tomadas_sobrepostas() {
        var sobrou = SubtracaoDeJanelas.subtrair(List.of(janela(0, 240)),
                List.of(janela(30, 120), janela(60, 90), janela(100, 150)));

        assertThat(sobrou).containsExactly(janela(0, 30), janela(150, 240));
    }

    @Test
    @DisplayName("tomada que cobre tudo não deixa nada")
    void tomada_que_cobre_tudo() {
        assertThat(SubtracaoDeJanelas.subtrair(List.of(janela(30, 90)), List.of(janela(0, 120))))
                .isEmpty();
    }

    @Test
    @DisplayName("tomada fora da janela é ignorada")
    void tomada_fora() {
        assertThat(SubtracaoDeJanelas.subtrair(List.of(janela(0, 60)), List.of(janela(120, 180))))
                .containsExactly(janela(0, 60));
    }

    @Test
    @DisplayName("capacidade conta cruzamentos, não sobreposição")
    void capacidade_conta() {
        // Com três lavatórios, duas lavagens simultâneas ainda deixam um livre. Um teste de
        // sobreposição diria "ocupado" e jogaria fora um terço da capacidade do salão.
        var tres = new CapacidadeDeRecurso(3, List.of(janela(0, 60), janela(0, 60)));

        assertThat(tres.cabeEm(janela(0, 30))).isTrue();
        assertThat(new CapacidadeDeRecurso(2, List.of(janela(0, 60), janela(0, 60)))
                .cabeEm(janela(0, 30))).isFalse();
    }

    @Test
    @DisplayName("recurso sem unidade não é recurso")
    void capacidade_precisa_de_unidade() {
        assertThatThrownBy(() -> new CapacidadeDeRecurso(0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
