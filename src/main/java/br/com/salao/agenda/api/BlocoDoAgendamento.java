package br.com.salao.agenda.api;

import br.com.salao.catalogo.api.TipoDeBloco;
import java.util.UUID;

/**
 * RT-AGD-002 — um bloco já materializado no tempo.
 *
 * <p>{@code profissionalId} nulo é a pausa: a cliente está no salão e o profissional está livre
 * para um encaixe. {@code recursoId} nulo é o bloco que não disputa equipamento.
 *
 * <p>Blocos que exigem mais de um recurso viram <strong>mais de uma linha com a mesma
 * {@code ordem}</strong> — a primeira carrega o profissional, as demais só o recurso. Agrupar por
 * {@code ordem} devolve a linha do tempo. É o caso raro: uma cliente usa uma cadeira e um
 * lavatório, não dois de cada.
 */
public record BlocoDoAgendamento(
        int ordem,
        TipoDeBloco tipo,
        Janela janela,
        UUID profissionalId,
        UUID recursoId,
        Integer unidade) {
}
