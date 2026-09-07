package br.com.salao.financeiro.internal.application;

import br.com.salao.financeiro.api.FormaDoVale;
import br.com.salao.shared.dinheiro.Money;
import java.util.UUID;

/**
 * RT-FIN-005 — o pedido de adiantamento.
 *
 * <p>Sem data: a competência é o dia do salão e é decidida aqui dentro (RN-FIN-014). Um vale com
 * data escolhida por quem chama poderia ser empurrado para dentro de um período já fechado, depois
 * de o repasse ter sido pago.
 *
 * @param motivo            opcional. "Recebi menos esse mês" é uma conversa; "o que foi esse vale
 *                          de R$ 300?" é outra, e o motivo é a única coisa que a encerra
 * @param chaveIdempotencia {@code Idempotency-Key}, ou {@code null}. Dinheiro saindo por duplo
 *                          clique é o erro que ninguém percebe até o fechamento
 */
public record NovoVale(
        UUID profissionalId,
        Money valor,
        FormaDoVale forma,
        String motivo,
        String chaveIdempotencia) {
}
