package br.com.salao.catalogo.api;

import br.com.salao.shared.dinheiro.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
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
}
