package br.com.salao.agenda.api;

import java.util.EnumSet;
import java.util.Set;

/**
 * RN-AGD-007 — a máquina de estados do agendamento.
 *
 * <p>Explícita, e não um punhado de {@code if} espalhado pelos seis casos de uso de escrita: sem
 * um lugar só, a validação diverge — um caso de uso permite reabrir um cancelado e outro não, e
 * nenhum dos dois está errado por escrito.
 *
 * <p>Os três terminais são terminais de verdade. Desfazer um cancelamento seria devolver à agenda
 * um horário que já foi oferecido a outra pessoa.
 */
public enum StatusAgendamento {

    SOLICITADO,
    CONFIRMADO,
    EM_ATENDIMENTO,
    CONCLUIDO,
    CANCELADO,
    NO_SHOW;

    /** Os status que ocupam o horário — o mesmo conjunto das constraints de exclusão (V25). */
    public static final Set<StatusAgendamento> OCUPAM_A_AGENDA =
            EnumSet.of(SOLICITADO, CONFIRMADO, EM_ATENDIMENTO);

    public boolean podeTransicionarPara(StatusAgendamento destino) {
        return switch (this) {
            case SOLICITADO -> destino == CONFIRMADO || destino == CANCELADO || destino == NO_SHOW;
            case CONFIRMADO -> destino == EM_ATENDIMENTO || destino == CANCELADO
                    || destino == NO_SHOW;
            case EM_ATENDIMENTO -> destino == CONCLUIDO || destino == CANCELADO;
            case CONCLUIDO, CANCELADO, NO_SHOW -> false;
        };
    }

    /** Se este status ainda toma o horário de alguém. */
    public boolean ocupaAAgenda() {
        return OCUPAM_A_AGENDA.contains(this);
    }

    /**
     * RT-AGD-003 — se ainda dá para arrastar na grade.
     *
     * <p>Um atendimento em curso não se move: a cliente já está na cadeira. Um terminal, muito
     * menos — mover o passado reescreveria o que aconteceu. Sobram os dois que ainda vão
     * acontecer.
     */
    public boolean podeSerMovido() {
        return this == SOLICITADO || this == CONFIRMADO;
    }

    /**
     * Em que status um agendamento nasce.
     *
     * <p>Quem marca no balcão já confirmou com a cliente ali — nasce {@code CONFIRMADO}. Quem
     * marca sozinho pelo site ou pelo WhatsApp nasce {@code SOLICITADO}, e o salão confirma: sem
     * essa distinção, a agenda de sábado encheria de horário que ninguém validou.
     */
    public static StatusAgendamento inicialPara(OrigemDoAgendamento origem) {
        return origem == OrigemDoAgendamento.PAINEL || origem == OrigemDoAgendamento.RECORRENCIA
                ? CONFIRMADO
                : SOLICITADO;
    }
}
