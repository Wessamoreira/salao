package br.com.salao.financeiro.api;

import br.com.salao.shared.dinheiro.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * RT-FIN-001 — um pagamento como as telas e os outros módulos o veem.
 *
 * @param taxaPrevista        quanto o adquirente retém. Nulo enquanto não há contrato cadastrado
 *                            (Fase 5) — e nulo não é zero: zero significaria taxa isenta
 * @param previsaoRecebimento quando o dinheiro cai. Nulo pela mesma razão
 */
public record PagamentoResumo(
        UUID id,
        UUID comandaId,
        FormaDePagamento forma,
        Money valor,
        String bandeira,
        int parcelas,
        String nsu,
        String autorizacao,
        Money taxaPrevista,
        LocalDate previsaoRecebimento,
        StatusDeConciliacao statusConciliacao,
        Instant criadoEm) {
}
