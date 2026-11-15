package br.com.salao.shared.agente;

import java.util.Set;

/**
 * RT-CNV-010 / ADR-0011 — o que se aprende parado no balcão, e que o agente pode responder a quem
 * não tem conta.
 *
 * <p>É uma {@link FerramentaDeLeitura} — e não um terceiro tipo ao lado dela e da escrita —, então
 * o orquestrador a executa pelo caminho que já existe. O que ela acrescenta é <em>para quem</em> é
 * oferecida.
 *
 * <p>Ela chama um caso de uso <strong>público por desenho</strong> do módulo dono: sem
 * {@code @PreAuthorize}, porque não há principal a conferir, e escrito para não conseguir devolver
 * mais do que o balcão devolve. A segunda tranca não desapareceu — mudou de natureza: é a FORMA do
 * caso de uso. Ele não devolve menos porque filtra; devolve menos porque não consulta mais.
 *
 * <h2>Os dois catálogos não se misturam, e não é por disciplina</h2>
 *
 * <p>O catálogo oferece estas <strong>só</strong> à visitante, e as outras <strong>só</strong> à
 * equipe. Além disso {@link #permissoesExigidas()} é vazio, e vazio já significa "nunca oferecida" a
 * um usuário (RN-CNV-010) — então uma destas não alcança a equipe nem se o filtro por tipo sumisse.
 * Duas barreiras independentes para o mesmo erro, que é o que se quer no caminho de quem não é
 * ninguém.
 */
public interface FerramentaDoBalcao extends FerramentaDeLeitura {

    /**
     * Nunca oferecida a um usuário da equipe: vazio é o valor que o catálogo já trata como
     * "perigosa, não livre".
     */
    @Override
    default Set<String> permissoesExigidas() {
        return Set.of();
    }
}
