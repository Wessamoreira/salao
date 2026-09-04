package br.com.salao.agenda.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.agenda.api.OrigemDoAgendamento;
import br.com.salao.agenda.api.StatusAgendamento;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** RN-AGD-007 — a tabela completa de transições. */
class StatusAgendamentoTest {

    /** O que a regra diz, escrito uma vez, para o teste não repetir a implementação. */
    private static final java.util.Map<StatusAgendamento, Set<StatusAgendamento>> PERMITIDAS =
            java.util.Map.of(
                    StatusAgendamento.SOLICITADO, EnumSet.of(StatusAgendamento.CONFIRMADO,
                            StatusAgendamento.CANCELADO, StatusAgendamento.NO_SHOW),
                    StatusAgendamento.CONFIRMADO, EnumSet.of(StatusAgendamento.EM_ATENDIMENTO,
                            StatusAgendamento.CANCELADO, StatusAgendamento.NO_SHOW),
                    StatusAgendamento.EM_ATENDIMENTO, EnumSet.of(StatusAgendamento.CONCLUIDO,
                            StatusAgendamento.CANCELADO),
                    StatusAgendamento.CONCLUIDO, EnumSet.noneOf(StatusAgendamento.class),
                    StatusAgendamento.CANCELADO, EnumSet.noneOf(StatusAgendamento.class),
                    StatusAgendamento.NO_SHOW, EnumSet.noneOf(StatusAgendamento.class));

    @ParameterizedTest
    @EnumSource(StatusAgendamento.class)
    @DisplayName("a tabela de transições bate, célula por célula")
    void tabela_completa(StatusAgendamento origem) {
        // Varre as 36 combinações. Um teste que só verificasse as válidas deixaria passar uma
        // implementação permissiva demais — que é o defeito perigoso aqui.
        for (StatusAgendamento destino : StatusAgendamento.values()) {
            assertThat(origem.podeTransicionarPara(destino))
                    .as("%s -> %s", origem, destino)
                    .isEqualTo(PERMITIDAS.get(origem).contains(destino));
        }
    }

    @Test
    @DisplayName("os três terminais são terminais de verdade")
    void terminais_nao_saem() {
        // Desfazer um cancelamento seria devolver à agenda um horário que já foi oferecido a
        // outra pessoa.
        for (StatusAgendamento terminal : Set.of(StatusAgendamento.CONCLUIDO,
                StatusAgendamento.CANCELADO, StatusAgendamento.NO_SHOW)) {
            for (StatusAgendamento destino : StatusAgendamento.values()) {
                assertThat(terminal.podeTransicionarPara(destino)).isFalse();
            }
        }
    }

    @Test
    @DisplayName("nenhum status transiciona para si mesmo")
    void nao_transiciona_para_si() {
        for (StatusAgendamento status : StatusAgendamento.values()) {
            assertThat(status.podeTransicionarPara(status)).as("%s", status).isFalse();
        }
    }

    @Test
    @DisplayName("os três que ocupam a agenda são os do EXCLUDE")
    void ocupam_a_agenda() {
        // Este conjunto precisa ser o mesmo do filtro das constraints de exclusão (V25). Se
        // divergirem, a aplicação esconde horário que o banco aceita, ou oferece o que ele recusa.
        assertThat(StatusAgendamento.OCUPAM_A_AGENDA).containsExactlyInAnyOrder(
                StatusAgendamento.SOLICITADO, StatusAgendamento.CONFIRMADO,
                StatusAgendamento.EM_ATENDIMENTO);
        assertThat(StatusAgendamento.CANCELADO.ocupaAAgenda()).isFalse();
        assertThat(StatusAgendamento.NO_SHOW.ocupaAAgenda())
                .as("no-show libera o horário como o cancelamento").isFalse();
    }

    @Test
    @DisplayName("só o que ainda vai acontecer pode ser movido")
    void moveis() {
        assertThat(StatusAgendamento.SOLICITADO.podeSerMovido()).isTrue();
        assertThat(StatusAgendamento.CONFIRMADO.podeSerMovido()).isTrue();
        assertThat(StatusAgendamento.EM_ATENDIMENTO.podeSerMovido())
                .as("a cliente já está na cadeira").isFalse();
        assertThat(StatusAgendamento.CONCLUIDO.podeSerMovido()).isFalse();
    }

    @Test
    @DisplayName("quem marca decide o status inicial")
    void status_inicial_por_origem() {
        assertThat(StatusAgendamento.inicialPara(OrigemDoAgendamento.PAINEL))
                .as("o balcão já falou com a cliente").isEqualTo(StatusAgendamento.CONFIRMADO);
        assertThat(StatusAgendamento.inicialPara(OrigemDoAgendamento.RECORRENCIA))
                .isEqualTo(StatusAgendamento.CONFIRMADO);
        assertThat(StatusAgendamento.inicialPara(OrigemDoAgendamento.WEB))
                .as("a cliente sozinha precisa de confirmação")
                .isEqualTo(StatusAgendamento.SOLICITADO);
        assertThat(StatusAgendamento.inicialPara(OrigemDoAgendamento.WHATSAPP))
                .isEqualTo(StatusAgendamento.SOLICITADO);
    }
}
