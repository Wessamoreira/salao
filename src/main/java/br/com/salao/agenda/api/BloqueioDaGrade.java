package br.com.salao.agenda.api;

import java.util.UUID;

/**
 * RT-AGD-007 — um pedaço da grade tomado sem cliente nenhum.
 *
 * <p>Exatamente um entre {@code profissionalId} e {@code recursoId} costuma vir preenchido, mas os
 * dois juntos são legítimos: "a Ana e o lavatório 1 estão fora das 14h às 15h" é uma frase que o
 * salão diz. O que não existe é bloqueio sem nenhum dos dois — some da grade e ninguém entende por
 * que o horário sumiu.
 */
public record BloqueioDaGrade(
        UUID id,
        UUID profissionalId,
        UUID recursoId,
        Integer unidade,
        MotivoDoBloqueioDeAgenda motivo,
        String descricao,
        Janela janela) {

    public BloqueioDaGrade {
        if (profissionalId == null && recursoId == null) {
            throw new IllegalArgumentException(
                    "um bloqueio precisa tomar o horário de alguém ou de alguma coisa");
        }
        if (motivo == null) {
            throw new IllegalArgumentException("o motivo do bloqueio é obrigatório");
        }
        if (janela == null) {
            throw new IllegalArgumentException("o bloqueio precisa de um período");
        }
    }
}
