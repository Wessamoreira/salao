package br.com.salao.equipe.api;

import br.com.salao.catalogo.api.SequenciaDeBlocos;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
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

    /**
     * RT-CAT-004 — a receita como <strong>esta pessoa</strong> a executa.
     *
     * <p>Devolve vazio quando o profissional não executa o serviço, está inativo, ou o serviço não
     * existe. Quando executa, devolve os blocos do catálogo — reescalados se houver duração
     * própria, iguais se não houver.
     *
     * <p>É uma pergunta só de propósito. A agenda precisa das duas coisas juntas — "ela faz?" e
     * "em quanto tempo?" — e deixá-las em dois métodos seria convidar algum chamador a usar a
     * duração do catálogo para alguém que leva quinze minutos a mais. O erro não apareceria: a
     * agenda só ficaria errada.
     */
    Optional<SequenciaDeBlocos> receitaDe(UUID profissionalId, UUID servicoId);

    // ------------------------------------------------------------------ RT-AGD-001

    /**
     * O que cada um destes profissionais atende nesta data, num punhado fixo de consultas.
     *
     * <p>Existe porque a disponibilidade pergunta pela equipe inteira: {@link #intervalosEm(UUID,
     * LocalDate)} num laço custaria duas consultas por profissional, e a grade do dia (RT-AGD-011)
     * pergunta por dez de uma vez. É a mesma razão de {@code ServicoApi.idsExistentes}.
     *
     * <p>Profissional sem jornada no dia não aparece no mapa — vazio significa "não atende", e
     * quem itera sobre as chaves já pula quem não trabalha.
     */
    Map<UUID, List<IntervaloDeTrabalho>> intervalosEm(Collection<UUID> profissionais,
                                                      LocalDate data);

    /**
     * A receita deste serviço para cada um destes profissionais, num punhado fixo de consultas.
     *
     * <p>Só entram os que executam o serviço e estão ativos — a mesma resposta de
     * {@link #receitaDe}, e pela mesma razão de ser uma pergunta só: quem tivesse de compor
     * habilidade e duração no chamador acabaria usando a duração do catálogo para quem leva outra,
     * e a agenda ficaria errada sem nada acusar.
     */
    Map<UUID, SequenciaDeBlocos> receitasDe(Collection<UUID> profissionais, UUID servicoId);
}
