package br.com.salao.estoque.api;

import br.com.salao.shared.dinheiro.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * RT-EST-008 — o que precisa de atenção no estoque hoje.
 *
 * <h2>Duas perguntas diferentes, uma tela</h2>
 *
 * <p>"O que está acabando?" e "o que vai vencer?" têm causas e ações diferentes — a primeira se
 * resolve comprando, a segunda usando ou descartando —, mas quem olha é a mesma pessoa, no mesmo
 * momento do dia. Separá-las em duas telas garantiria que uma delas nunca fosse aberta.
 *
 * @param abaixoDoMinimo só produtos com mínimo DEFINIDO. Quem não tem mínimo não aparece: nulo
 *                       não é zero, e um alerta que ninguém lê ocupa o lugar do que importa
 * @param vencendo       lotes com validade dentro da janela pedida, e os já vencidos
 */
public record AlertasDeEstoque(
        int diasDeAviso,
        List<ProdutoEmFalta> abaixoDoMinimo,
        List<LoteVencendo> vencendo) {

    /**
     * @param minimo    o que o dono definiu
     * @param faltando  quanto falta para voltar ao mínimo. É o número do pedido de compra
     */
    public record ProdutoEmFalta(
            UUID produtoId,
            String nome,
            UnidadeDeMedida unidade,
            BigDecimal quantidade,
            BigDecimal minimo,
            Money valorEmEstoque) {

        public BigDecimal faltando() {
            return minimo.subtract(quantidade).max(BigDecimal.ZERO);
        }

        /** Acabou de verdade, e não só cruzou o mínimo. */
        public boolean zerado() {
            return quantidade.signum() <= 0;
        }
    }

    /**
     * @param diasRestantes negativo quando já venceu — e vencido aparece na mesma lista, porque a
     *                      ação é a mesma: tirar da prateleira
     */
    public record LoteVencendo(
            UUID loteId,
            UUID produtoId,
            String produto,
            String codigo,
            BigDecimal quantidade,
            LocalDate validade,
            long diasRestantes,
            Money custoEmRisco) {

        public boolean vencido() {
            return diasRestantes < 0;
        }
    }

    public boolean vazio() {
        return abaixoDoMinimo.isEmpty() && vencendo.isEmpty();
    }

    /** O dinheiro parado em lote que vai vencer. É o número que faz o dono agir. */
    public Money custoEmRisco() {
        return vencendo.stream()
                .map(LoteVencendo::custoEmRisco)
                .reduce(Money.ZERO, Money::somar);
    }
}
