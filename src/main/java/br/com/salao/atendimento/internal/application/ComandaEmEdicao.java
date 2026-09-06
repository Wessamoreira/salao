package br.com.salao.atendimento.internal.application;

import br.com.salao.atendimento.api.ComandaResumo;
import br.com.salao.atendimento.api.ErrosDoAtendimento;
import br.com.salao.atendimento.api.ItemDaComanda;
import br.com.salao.atendimento.api.StatusDaComanda;
import br.com.salao.atendimento.internal.infra.ComandasJdbc;
import java.math.BigDecimal;
import java.util.UUID;
import br.com.salao.shared.erro.ErroDeDominio;

/**
 * As guardas que toda alteração de comanda repete.
 *
 * <p>Existiam copiadas em cinco casos de uso — e junto com elas o mesmo código de erro declarado
 * cinco vezes. Uma cópia que diverge das outras não quebra teste nenhum: só faz a mesma recusa
 * responder diferente conforme a rota.
 */
final class ComandaEmEdicao {

    private ComandaEmEdicao() {
    }

    /** A comanda, se existir e ainda aceitar alteração (RN-ATD-006). */
    static ComandaResumo exigirAberta(ComandasJdbc comandas, UUID comandaId) {
        ComandaResumo comanda = comandas.porId(comandaId)
                .orElseThrow(() -> new ErroDeDominio(ErrosDoAtendimento.COMANDA_NAO_ENCONTRADA,
                        "comanda " + comandaId + " não encontrada"));
        if (comanda.status() != StatusDaComanda.ABERTA) {
            throw new ErroDeDominio(ErrosDoAtendimento.COMANDA_NAO_ESTA_ABERTA,
                    "a comanda " + comanda.numero() + " está " + comanda.status());
        }
        return comanda;
    }

    /** O item, se for mesmo daquela comanda. */
    static ItemDaComanda exigirItem(ComandasJdbc comandas, UUID comandaId, UUID itemId) {
        return comandas.itensDe(comandaId).stream()
                .filter(i -> i.id().equals(itemId)).findFirst()
                .orElseThrow(() -> new ErroDeDominio(ErrosDoAtendimento.ITEM_NAO_ENCONTRADO,
                        "item " + itemId + " não é da comanda " + comandaId));
    }

    /** Nula vira 1 — o caso normal é uma vez. Zero ou negativa é erro de digitação. */
    static BigDecimal quantidadeValida(BigDecimal quantidade) {
        BigDecimal quantas = quantidade == null ? BigDecimal.ONE : quantidade;
        if (quantas.signum() <= 0) {
            throw new ErroDeDominio(ErrosDoAtendimento.QUANTIDADE_INVALIDA,
                    "quantidade informada: " + quantas);
        }
        return quantas;
    }
}
