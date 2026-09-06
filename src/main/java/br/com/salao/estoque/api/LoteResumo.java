package br.com.salao.estoque.api;

import br.com.salao.shared.dinheiro.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * RT-EST-002 — a caixa que chegou.
 *
 * @param validade   nula quando o produto não tem validade impressa. No FEFO, o que não vence sai
 *                   depois do que vence
 * @param quantidade quanto ainda há neste lote — <strong>projeção</strong> dos movimentos, nunca
 *                   coluna. Não existe saldo gravado para desandar
 */
public record LoteResumo(
        UUID id,
        UUID produtoId,
        String codigo,
        Money custoUnitario,
        BigDecimal quantidade,
        LocalDate validade,
        LocalDate recebidoEm) {

    /** Se o lote já não tem nada. FEFO pula estes. */
    public boolean vazio() {
        return quantidade.signum() <= 0;
    }

    /** Vencido na data informada. Nulo em {@code validade} nunca vence. */
    public boolean vencidoEm(LocalDate data) {
        return validade != null && validade.isBefore(data);
    }
}
