package br.com.salao.estoque.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * RT-EST-006 — a contagem física, com as divergências já calculadas.
 *
 * @param itens uma linha por lote contado. Lote não contado não aparece — e é de propósito: um
 *              zero implícito faria o inventário parcial zerar o estoque que ninguém olhou
 */
public record InventarioDeEstoque(
        UUID id,
        StatusDoInventario status,
        String observacao,
        Instant criadoEm,
        Instant encerradoEm,
        List<ItemContado> itens) {

    /**
     * @param esperada  o saldo do lote <strong>no instante da contagem</strong>, congelado ali
     * @param contada   o que a pessoa achou na prateleira
     */
    public record ItemContado(
            UUID loteId,
            UUID produtoId,
            String produto,
            String loteCodigo,
            BigDecimal esperada,
            BigDecimal contada,
            String observacao,
            Instant contadoEm) {

        /**
         * O que o ajuste vai escrever: negativo faltou, positivo apareceu.
         *
         * <p>É um <strong>delta</strong>, não um "passe a valer o contado". Movimento legítimo
         * depois da contagem continua valendo em cima da correção.
         */
        public BigDecimal divergencia() {
            return contada.subtract(esperada);
        }

        public boolean bateu() {
            return divergencia().signum() == 0;
        }
    }

    /** Quantas linhas vão virar movimento. Zero significa que a contagem bateu inteira. */
    public int quantasDivergem() {
        return (int) itens.stream().filter(item -> !item.bateu()).count();
    }

    /** A soma das divergências, para a frase da confirmação (R-UX-05). */
    public BigDecimal saldoDasDivergencias() {
        return itens.stream()
                .map(ItemContado::divergencia)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
