package br.com.salao.equipe.api;

import java.time.LocalDate;
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

    /**
     * RT-EQP-003 — o que o profissional atende <strong>numa data concreta</strong>.
     *
     * <p>É a jornada daquele dia da semana menos os bloqueios que alcançam a data: férias,
     * feriado do salão, curso da tarde. É esta a resposta que a agenda usa (RT-AGD-001), e não a
     * jornada crua — a jornada diz "terça das 9h às 19h", e no dia 14 a pessoa está em curso das
     * 14h às 16h.
     *
     * <p>Devolve os intervalos em ordem, já partidos: um curso no meio do expediente produz dois.
     */
    List<IntervaloDeTrabalho> intervalosEm(UUID profissionalId, LocalDate data);

    /** Os bloqueios de um período, do profissional e do salão inteiro. */
    List<BloqueioDeJornada> bloqueiosEntre(UUID profissionalId, LocalDate inicio, LocalDate fim);

    /**
     * RT-EQP-004 — se este profissional executa este serviço.
     *
     * <p>É a pergunta de RN-AGD-001, e é por aqui que a agenda a faz: a habilidade vive neste
     * módulo, e join entre módulos é proibido.
     *
     * <p>Devolve {@code false} para profissional inativo, sem exceção: quem não trabalha mais não
     * executa nada, e obrigar quem chama a checar as duas coisas seria convidar a esquecer uma.
     */
    boolean executa(UUID profissionalId, UUID servicoId);

    /** O regime e a lista de um profissional — o que a tela de cadastro mostra. */
    Habilidades habilidadesDe(UUID profissionalId);

    /**
     * Quem pode fazer este serviço, entre os ativos.
     *
     * <p>É a consulta da tela de marcação, e inclui tanto quem tem o serviço na lista quanto quem
     * está no regime "faz o cardápio inteiro".
     */
    List<ProfissionalResumo> queExecutam(UUID servicoId);
}
