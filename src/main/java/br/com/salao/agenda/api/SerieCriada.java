package br.com.salao.agenda.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * RT-AGD-009 — o resultado de criar uma série, incluindo o que não coube.
 *
 * <p>É a diferença desta rotina para RT-AGD-008. Lá, tudo ou nada: o empurrão é um ato só, e uma
 * agenda empurrada pela metade é pior do que uma não empurrada. Aqui <strong>cada ocorrência é um
 * compromisso independente</strong> — a terceira quinta é feriado, ou o horário já está tomado, e
 * recusar as outras onze por causa dela seria absurdo.
 *
 * <p>Por isso a recusa faz parte da resposta em vez de virar exceção: {@code naoCouberam} é
 * informação que a recepção precisa ver e resolver uma a uma, não um erro que apaga o trabalho
 * inteiro.
 *
 * @param naoCouberam os instantes que ficaram de fora, na ordem em que teriam acontecido
 */
public record SerieCriada(
        Recorrencia recorrencia,
        List<UUID> agendamentos,
        List<Instant> naoCouberam) {

    public SerieCriada {
        agendamentos = List.copyOf(agendamentos);
        naoCouberam = List.copyOf(naoCouberam);
    }

    /** Quantas de fato foram marcadas — o número que a recepção lê primeiro. */
    public int marcadas() {
        return agendamentos.size();
    }
}
