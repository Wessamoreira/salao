package br.com.salao.catalogo.api;

import br.com.salao.shared.dinheiro.Money;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * RT-CAT-001 — contrato público do módulo {@code catalogo}.
 *
 * <p>É por aqui que {@code agenda} descobre a receita do serviço — quanto tempo reservar e quando
 * o profissional fica livre — e {@code atendimento} descobre o que foi executado.
 */
public interface ServicoApi {

    Optional<ServicoResumo> porId(UUID servicoId);

    /** O cardápio: o que se pode marcar hoje. Sem paginação — um salão tem dezenas, não milhares. */
    List<ServicoResumo> ativos();

    /**
     * RT-CAT-002 — quanto o serviço custava (ou vai custar) numa data.
     *
     * <p>Devolve vazio quando não há preço definido até aquela data, e é <strong>de propósito que
     * não devolva zero</strong>: zero é um preço, e um serviço sem preço num relatório financeiro
     * precisa aparecer como pendência de cadastro, não como brinde.
     *
     * <p>Quem chama sempre informa a data: pedir "o preço" sem dizer quando é a pergunta que
     * produz o relatório errado.
     */
    Optional<Money> precoEm(UUID servicoId, LocalDate data);

    /** O histórico completo, do mais recente para o mais antigo. */
    List<PrecoVigente> historicoDePrecos(UUID servicoId);

    /**
     * Quais destes ids são serviços <strong>deste salão</strong>, numa consulta só.
     *
     * <p>Existe para quem precisa validar um conjunto — {@code equipe} conferindo habilidades
     * (RT-EQP-004) é o primeiro caso. Chamar {@link #porId} num laço custaria uma consulta por
     * serviço, e {@code porId} ainda traz os blocos, que quem só quer saber se existe não usa.
     *
     * <p>Inclui os desativados de propósito: um serviço fora do cardápio hoje pode voltar, e
     * recusar a habilidade dele faria a informação se perder no caminho.
     */
    Set<UUID> idsExistentes(Collection<UUID> candidatos);
}
