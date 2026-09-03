package br.com.salao.catalogo.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * RT-CAT-003 — contrato público dos recursos do salão.
 *
 * <p>É por aqui que {@code agenda} descobre <strong>quantas unidades existem</strong> de cada coisa
 * disputada. Quanto tempo cada uma fica ocupada não está aqui: está no bloco do serviço, porque a
 * coloração toma o lavatório em 30 dos seus 100 minutos.
 */
public interface RecursoApi {

    Optional<Recurso> porId(UUID recursoId);

    /** O que o salão tem hoje. Sem paginação — são unidades, não catálogo. */
    List<Recurso> ativos();
}
