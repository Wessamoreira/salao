package br.com.salao.agenda.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.agenda.api.EsperaNaFila;
import br.com.salao.agenda.internal.domain.CandidatasDaVaga.Candidata;
import br.com.salao.agenda.internal.domain.CandidatasDaVaga.Vaga;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Domínio puro: quem cabe na vaga (RN-AGD-025) e quem seria pulada (RN-AGD-026). */
class CandidatasDaVagaTest {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate QUINTA = LocalDate.of(2026, 9, 24);
    private static final Instant AGORA = QUINTA.minusDays(2).atStartOfDay(SAO_PAULO).toInstant();

    private final UUID ana = UUID.randomUUID();
    private final UUID julia = UUID.randomUUID();
    private final UUID corte = UUID.randomUUID();
    private final UUID escova = UUID.randomUUID();

    /** A vaga da Ana, das 14h às 16h da quinta. */
    private final Vaga vaga = new Vaga(ana, as(14, 0), as(16, 0));

    private static Instant as(int hora, int minuto) {
        return QUINTA.atTime(LocalTime.of(hora, minuto)).atZone(SAO_PAULO).toInstant();
    }

    private EsperaNaFila espera(UUID servico, UUID profissional, LocalDate de, LocalDate ate,
                                LocalTime horaDe, LocalTime horaAte, Instant desde) {
        return new EsperaNaFila(UUID.randomUUID(), UUID.randomUUID(), servico, profissional, de,
                ate, horaDe, horaAte, null, desde, null);
    }

    private EsperaNaFila espera(UUID servico, Instant desde) {
        return espera(servico, null, QUINTA, QUINTA, null, null, desde);
    }

    private List<Candidata> candidatas(List<EsperaNaFila> esperas,
                                       Map<UUID, List<Instant>> horarios) {
        return CandidatasDaVaga.de(vaga, esperas, horarios, AGORA, SAO_PAULO);
    }

    @Test
    @DisplayName("o horário precisa começar dentro da vaga; terminar depois pode")
    void comeca_dentro_da_vaga() {
        // 13h45 já estava livre antes do cancelamento: não é o tempo que abriu. 15h45 começa dentro
        // e termina depois — se o tempo seguinte estava livre, a disponibilidade já disse que cabe.
        var marilda = espera(corte, AGORA);

        var lista = candidatas(List.of(marilda), Map.of(corte,
                List.of(as(13, 45), as(14, 0), as(15, 45), as(16, 0))));

        assertThat(lista).singleElement().satisfies(c ->
                assertThat(c.horarios()).containsExactly(as(14, 0), as(15, 45)));
    }

    @Test
    @DisplayName("a faixa de hora vale no início do horário, em relógio de parede")
    void faixa_de_hora() {
        var aTarde = espera(corte, null, QUINTA, QUINTA, LocalTime.of(15, 0),
                LocalTime.of(18, 0), AGORA);
        var deManha = espera(corte, null, QUINTA, QUINTA, LocalTime.of(9, 0),
                LocalTime.of(12, 0), AGORA.plusSeconds(1));

        var lista = candidatas(List.of(aTarde, deManha),
                Map.of(corte, List.of(as(14, 0), as(14, 30), as(15, 0), as(15, 30))));

        assertThat(lista).singleElement().satisfies(c -> {
            assertThat(c.espera()).isEqualTo(aTarde);
            assertThat(c.horarios()).containsExactly(as(15, 0), as(15, 30));
        });
    }

    @Test
    @DisplayName("quem pediu outra profissional não é candidata; quem não pediu, é")
    void profissional_da_vaga() {
        var querJulia = espera(corte, julia, QUINTA, QUINTA, null, null, AGORA);
        var querAna = espera(corte, ana, QUINTA, QUINTA, null, null, AGORA.plusSeconds(1));
        var qualquer = espera(corte, null, QUINTA, QUINTA, null, null, AGORA.plusSeconds(2));

        var lista = candidatas(List.of(querJulia, querAna, qualquer),
                Map.of(corte, List.of(as(14, 0))));

        assertThat(lista).extracting(Candidata::espera).containsExactly(querAna, qualquer);
    }

    @Test
    @DisplayName("o dia da vaga precisa estar na janela de dias da espera")
    void dia_da_janela() {
        var outraSemana = espera(corte, null, QUINTA.plusDays(1), QUINTA.plusDays(7), null, null,
                AGORA);
        var estaSemana = espera(corte, null, QUINTA.minusDays(3), QUINTA, null, null,
                AGORA.plusSeconds(1));

        var lista = candidatas(List.of(outraSemana, estaSemana), Map.of(corte, List.of(as(14, 0))));

        assertThat(lista).extracting(Candidata::espera).containsExactly(estaSemana);
    }

    @Test
    @DisplayName("ordem de chegada, e a posição conta só quem cabe")
    void ordem_de_chegada() {
        // Entregues fora de ordem de propósito: a ordem é a de quem chegou, não a de quem o banco
        // devolveu. E quem não cabe não ocupa posição — a 2ª é a 2ª a quem ligar.
        var terceira = espera(corte, AGORA.plusSeconds(30));
        var primeira = espera(corte, AGORA.plusSeconds(10));
        var naoCabe = espera(escova, AGORA.plusSeconds(15));
        var segunda = espera(corte, AGORA.plusSeconds(20));

        var lista = candidatas(List.of(terceira, primeira, naoCabe, segunda),
                Map.of(corte, List.of(as(14, 0))));

        assertThat(lista).extracting(Candidata::espera)
                .containsExactly(primeira, segunda, terceira);
        assertThat(lista).extracting(Candidata::posicao).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("sem horário que caiba não é candidata — serviço que ela não faz inclusive")
    void sem_horario_nao_e_candidata() {
        var escovaSemHorario = espera(escova, AGORA);
        var corteComHorario = espera(corte, AGORA.plusSeconds(1));

        var lista = candidatas(List.of(escovaSemHorario, corteComHorario),
                Map.of(corte, List.of(as(14, 0)), escova, List.of()));

        assertThat(lista).extracting(Candidata::espera).containsExactly(corteComHorario);
    }

    @Test
    @DisplayName("qualquer serviço que caiba: cada espera com os horários do seu serviço")
    void servicos_diferentes_na_mesma_vaga() {
        var querCorte = espera(corte, AGORA);
        var querEscova = espera(escova, AGORA.plusSeconds(1));

        var lista = candidatas(List.of(querCorte, querEscova), Map.of(
                corte, List.of(as(14, 0), as(15, 30)),
                escova, List.of(as(14, 0))));

        assertThat(lista).hasSize(2);
        assertThat(lista.get(0).horarios()).containsExactly(as(14, 0), as(15, 30));
        assertThat(lista.get(1).horarios()).containsExactly(as(14, 0));
    }

    @Test
    @DisplayName("horário que já passou não é oferecido")
    void passado_fica_de_fora() {
        var marilda = espera(corte, AGORA.minusSeconds(60));

        var lista = CandidatasDaVaga.de(vaga, List.of(marilda),
                Map.of(corte, List.of(as(14, 0), as(15, 0))), as(14, 30), SAO_PAULO);

        assertThat(lista).singleElement()
                .satisfies(c -> assertThat(c.horarios()).containsExactly(as(15, 0)));
    }

    // ------------------------------------------------------------------ quem seria pulada

    @Test
    @DisplayName("chamar a primeira não pula ninguém")
    void primeira_nao_pula() {
        var lista = candidatas(List.of(espera(corte, AGORA), espera(corte, AGORA.plusSeconds(1))),
                Map.of(corte, List.of(as(14, 0))));

        assertThat(CandidatasDaVaga.puladaAoChamar(lista, lista.get(0).espera().id(), Set.of()))
                .isEmpty();
    }

    @Test
    @DisplayName("chamar a segunda com a primeira sem chamada é pulo — e diz quem seria pulada")
    void segunda_pula_a_primeira() {
        var lista = candidatas(List.of(espera(corte, AGORA), espera(corte, AGORA.plusSeconds(1)),
                espera(corte, AGORA.plusSeconds(2))), Map.of(corte, List.of(as(14, 0))));

        assertThat(CandidatasDaVaga.puladaAoChamar(lista, lista.get(2).espera().id(),
                Set.of(lista.get(0).espera().id())))
                .as("a primeira já foi chamada; a pulada é a segunda")
                .hasValueSatisfying(pulada -> assertThat(pulada.posicao()).isEqualTo(2));
    }

    @Test
    @DisplayName("com todas as de antes já chamadas, não há pulo")
    void antes_todas_chamadas() {
        var lista = candidatas(List.of(espera(corte, AGORA), espera(corte, AGORA.plusSeconds(1))),
                Map.of(corte, List.of(as(14, 0))));

        assertThat(CandidatasDaVaga.puladaAoChamar(lista, lista.get(1).espera().id(),
                Set.of(lista.get(0).espera().id()))).isEmpty();
    }
}
