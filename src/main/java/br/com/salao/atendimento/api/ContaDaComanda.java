package br.com.salao.atendimento.api;

import br.com.salao.financeiro.api.PagamentoResumo;
import br.com.salao.shared.dinheiro.Money;
import java.util.List;

/**
 * RT-FIN-001 — a comanda do ponto de vista de quem vai cobrar.
 *
 * <p>{@code restante} é o número que a tela de fechamento mostra o tempo todo (R-UX-08): *"o
 * restante a pagar é recalculado a cada forma adicionada e fica sempre visível"*. Ele vem pronto do
 * servidor pela mesma razão de tudo o mais neste módulo — subtrair no front seria a mesma conta em
 * dois lugares, e a divergência apareceria como um centavo que impede o fechamento.
 *
 * @param totalPago soma do que já foi registrado, inclusive cortesia e fiado: as duas quitam a
 *                  comanda, ainda que uma nunca vire dinheiro
 */
public record ContaDaComanda(
        ComandaResumo comanda,
        List<PagamentoResumo> pagamentos,
        Money totalPago,
        Money restante) {

    /** Se ainda falta receber alguma coisa. É o que impede o fechamento (RT-ATD-006). */
    public boolean temSaldoAberto() {
        return restante.valor().signum() > 0;
    }
}
