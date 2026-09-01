package br.com.salao.catalogo.api;

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
}
