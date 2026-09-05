package br.com.salao.agenda.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.salao.agenda.api.EsperaNaFila;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Domínio puro: se a vaga serve para quem está esperando. */
class EsperaNaFilaTest {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate QUINTA = LocalDate.of(2026, 9, 17);

    private final UUID escova = UUID.randomUUID();
    private final UUID corte = UUID.randomUUID();
    private final UUID ana = UUID.randomUUID();
    private final UUID julia = UUID.randomUUID();

    private EsperaNaFila espera(UUID servico, UUID profissional, LocalDate de, LocalDate ate,
                                LocalTime horaDe, LocalTime horaAte) {
        return new EsperaNaFila(UUID.randomUUID(), UUID.randomUUID(), servico, profissional,
                de, ate, horaDe, horaAte, null, Instant.now());
    }

    private Instant em(LocalDate dia, int hora) {
        return dia.atTime(LocalTime.of(hora, 0)).atZone(SAO_PAULO).toInstant();
    }

    @Test
    @DisplayName("a vaga serve quando bate serviço, profissional, dia e hora")
    void serve_quando_tudo_bate() {
        var esperando = espera(escova, ana, QUINTA, QUINTA, LocalTime.of(13, 0),
                LocalTime.of(18, 0));

        assertThat(esperando.serve(escova, ana, em(QUINTA, 14), SAO_PAULO)).isTrue();
    }

    @Test
    @DisplayName("sem profissional na espera, qualquer um serve")
    void sem_profissional_aceita_qualquer_um() {
        // É o caso em que a fila mais funciona: qualquer cancelamento serve.
        var esperando = espera(escova, null, QUINTA, QUINTA, null, null);

        assertThat(esperando.serve(escova, ana, em(QUINTA, 14), SAO_PAULO)).isTrue();
        assertThat(esperando.serve(escova, julia, em(QUINTA, 14), SAO_PAULO)).isTrue();
    }

    @Test
    @DisplayName("com profissional na espera, só ele serve")
    void com_profissional_e_exclusivo() {
        var esperando = espera(escova, ana, QUINTA, QUINTA, null, null);

        assertThat(esperando.serve(escova, julia, em(QUINTA, 14), SAO_PAULO)).isFalse();
    }

    @Test
    @DisplayName("outro serviço nunca serve")
    void servico_precisa_bater() {
        var esperando = espera(escova, null, QUINTA, QUINTA, null, null);

        assertThat(esperando.serve(corte, ana, em(QUINTA, 14), SAO_PAULO)).isFalse();
    }

    @Test
    @DisplayName("sem faixa de hora, qualquer horário do dia serve")
    void sem_hora_aceita_o_dia_inteiro() {
        var esperando = espera(escova, null, QUINTA, QUINTA, null, null);

        assertThat(esperando.serve(escova, ana, em(QUINTA, 9), SAO_PAULO)).isTrue();
        assertThat(esperando.serve(escova, ana, em(QUINTA, 19), SAO_PAULO)).isTrue();
    }

    @Test
    @DisplayName("a faixa de hora é fechada no início e aberta no fim")
    void faixa_de_hora() {
        // A mesma semântica de Janela (RN-AGD-010): quem aceita "das 13h às 18h" aceita as 13h em
        // ponto e não aceita as 18h, que é quando ela precisa ir embora.
        var esperando = espera(escova, null, QUINTA, QUINTA, LocalTime.of(13, 0),
                LocalTime.of(18, 0));

        assertThat(esperando.serve(escova, ana, em(QUINTA, 13), SAO_PAULO)).isTrue();
        assertThat(esperando.serve(escova, ana, em(QUINTA, 17), SAO_PAULO)).isTrue();
        assertThat(esperando.serve(escova, ana, em(QUINTA, 18), SAO_PAULO)).isFalse();
        assertThat(esperando.serve(escova, ana, em(QUINTA, 12), SAO_PAULO)).isFalse();
    }

    @Test
    @DisplayName("o intervalo de dias é fechado dos dois lados")
    void intervalo_de_dias() {
        // "De segunda a sexta" inclui a sexta: é como as pessoas falam.
        var esperando = espera(escova, null, QUINTA, QUINTA.plusDays(2), null, null);

        assertThat(esperando.serve(escova, ana, em(QUINTA, 14), SAO_PAULO)).isTrue();
        assertThat(esperando.serve(escova, ana, em(QUINTA.plusDays(2), 14), SAO_PAULO)).isTrue();
        assertThat(esperando.serve(escova, ana, em(QUINTA.plusDays(3), 14), SAO_PAULO)).isFalse();
        assertThat(esperando.serve(escova, ana, em(QUINTA.minusDays(1), 14), SAO_PAULO)).isFalse();
    }

    @Test
    @DisplayName("a comparação é em relógio de parede, no fuso do salão")
    void hora_de_parede_e_nao_instante() {
        // A cliente disse "quinta à tarde", que é uma frase sobre o relógio da parede. Comparar
        // instantes faria a faixa escorregar uma hora na virada do horário de verão.
        var esperando = espera(escova, null, QUINTA, QUINTA, LocalTime.of(13, 0),
                LocalTime.of(18, 0));
        Instant quatorzeEmSaoPaulo = em(QUINTA, 14);

        assertThat(esperando.serve(escova, ana, quatorzeEmSaoPaulo, SAO_PAULO)).isTrue();
        assertThat(esperando.serve(escova, ana, quatorzeEmSaoPaulo, ZoneId.of("UTC")))
                .as("o mesmo instante são 17h em UTC — ainda dentro da faixa").isTrue();
        assertThat(esperando.serve(escova, ana, em(QUINTA, 16), ZoneId.of("UTC")))
                .as("16h em São Paulo são 19h em UTC — fora da faixa").isFalse();
    }

    @Test
    @DisplayName("uma hora só não descreve faixa nenhuma")
    void hora_precisa_vir_em_par() {
        assertThatThrownBy(() -> espera(escova, null, QUINTA, QUINTA, LocalTime.of(13, 0), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> espera(escova, null, QUINTA, QUINTA, null, LocalTime.of(18, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("janela invertida é recusada")
    void janela_invertida() {
        assertThatThrownBy(() -> espera(escova, null, QUINTA, QUINTA.minusDays(1), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> espera(escova, null, QUINTA, QUINTA, LocalTime.of(18, 0),
                LocalTime.of(13, 0))).isInstanceOf(IllegalArgumentException.class);
    }
}
