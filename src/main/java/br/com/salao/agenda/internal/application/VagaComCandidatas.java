package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.internal.domain.ResultadoDaChamada;
import br.com.salao.agenda.internal.domain.OrigemDaVaga;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * RT-AGD-010 — uma vaga aberta como a recepção a vê: de quem é o tempo, e quem ligar.
 */
public record VagaComCandidatas(
        UUID id,
        UUID profissionalId,
        String profissional,
        Instant inicio,
        Instant fim,
        OrigemDaVaga origem,
        List<Candidata> candidatas) {

    public VagaComCandidatas {
        candidatas = List.copyOf(candidatas);
    }

    /**
     * Uma espera que cabe na vaga, com o que a recepção precisa para ligar.
     *
     * @param posicao     a ordem de chegada entre as candidatas desta vaga, a partir de 1
     * @param telefone    só dígitos, em E.164 sem {@code +}; formatar é do front. Nulo quando não há
     * @param horarios    os inícios possíveis nesta vaga, em ordem
     * @param chamadas    o que já foi tentado nesta vaga, na ordem em que aconteceu
     * @param exigeMotivo se chamá-la agora pularia alguém (RN-AGD-026). A tela pede o motivo antes;
     *                    quem decide é o servidor, que recusa de novo se a tela estiver velha
     */
    public record Candidata(
            UUID esperaId,
            int posicao,
            UUID clienteId,
            String cliente,
            String telefone,
            UUID servicoId,
            String servico,
            LocalDate aceitaDe,
            LocalDate aceitaAte,
            LocalTime horaDe,
            LocalTime horaAte,
            Instant naFilaDesde,
            List<Instant> horarios,
            List<Chamada> chamadas,
            boolean exigeMotivo) {

        public Candidata {
            horarios = List.copyOf(horarios);
            chamadas = List.copyOf(chamadas);
        }
    }

    /** Uma ligação já registrada. {@code quem} é o nome de quem registrou. */
    public record Chamada(ResultadoDaChamada resultado, Instant registradaEm, String quem,
                          String motivoDoPulo) {
    }
}
