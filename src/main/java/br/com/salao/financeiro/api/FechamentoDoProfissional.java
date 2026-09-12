package br.com.salao.financeiro.api;

import br.com.salao.shared.dinheiro.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * RT-FIN-006 — o contracheque de uma pessoa num período.
 *
 * <p>Congelado no momento do fechamento e nunca recalculado. Reprocessar um fechamento de três
 * meses atrás produz número diferente do repasse já pago (C4 em {@code 02-decisoes-estruturais}),
 * e aí não se explica a diferença a quem recebeu.
 *
 * @param saldoAnterior       o acumulado antes do início do período. Positivo é o salão devendo;
 *                            negativo é a pessoa devendo, por ter adiantado mais do que ganhou
 * @param movimentoDoPeriodo  o que o período produziu, já no sinal de quem recebe: comissão soma,
 *                            vale subtrai
 * @param valorRepassado      o que foi efetivamente pago. Nunca negativo — quando a conta dá
 *                            negativo não há repasse, e a dívida atravessa
 */
public record FechamentoDoProfissional(
        UUID id,
        UUID profissionalId,
        LocalDate periodoInicio,
        LocalDate periodoFim,
        Money saldoAnterior,
        Money movimentoDoPeriodo,
        Money valorRepassado,
        Instant criadoEm) {

    /**
     * O que sobra para o próximo período — zero quando tudo foi pago, negativo quando a pessoa
     * ficou devendo.
     *
     * <p>Derivado e não coluna: é aritmética dos três números que já estão gravados, e uma quarta
     * coluna seria a que discorda das outras três depois de alguém corrigir uma delas.
     */
    public Money saldoATransportar() {
        return saldoAnterior.somar(movimentoDoPeriodo).subtrair(valorRepassado);
    }
}
