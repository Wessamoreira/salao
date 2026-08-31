package br.com.salao.equipe.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * RT-EQP-001 — contrato público do módulo {@code equipe}.
 *
 * <p>É por aqui que {@code agenda} descobre quem pode atender e {@code financeiro} descobre a
 * comissão. Nenhum deles consulta a tabela diretamente.
 */
public interface ProfissionalApi {

    Optional<ProfissionalResumo> porId(UUID profissionalId);

    /**
     * Quem está ativo, para montar agenda e escolher quem atende.
     *
     * <p>Sem paginação: um salão tem de três a trinta profissionais. A regra de paginar existe
     * para listagem que cresce sem limite, e esta não cresce.
     */
    List<ProfissionalResumo> ativos();

    /** Confirma que o profissional existe e está ativo no tenant atual. */
    boolean estaAtivo(UUID profissionalId);

    /**
     * RT-EQP-002 — a jornada de uma semana comum.
     *
     * <p>Primeiro insumo da disponibilidade (RT-AGD-001), que é esta jornada menos os bloqueios
     * (RT-EQP-003) e menos os horários já ocupados. Profissional sem jornada cadastrada devolve
     * jornada vazia — e vazia significa "não atende", não "atende sempre": o padrão precisa ser o
     * que não marca horário para alguém que não trabalha.
     */
    JornadaSemanal jornadaDe(UUID profissionalId);
}
