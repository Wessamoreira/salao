package br.com.salao.conversacional.internal.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * RT-CNV-004 — quanto o provedor cobra, em dólar por milhão de tokens.
 *
 * <p>Vem de configuração, sem valor padrão (ver {@code application.yml}): sem preço não há custo, e
 * sem custo não há teto. {@link #configurado()} falso para o agente inteiro.
 */
public record PrecoDoModelo(BigDecimal entradaPorMilhao, BigDecimal saidaPorMilhao) {

    private static final BigDecimal MILHAO = BigDecimal.valueOf(1_000_000);

    /** Texto vazio ou inválido vira ausência de preço — nunca zero, que seria "de graça". */
    public static PrecoDoModelo de(String entrada, String saida) {
        return new PrecoDoModelo(numero(entrada), numero(saida));
    }

    private static BigDecimal numero(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            BigDecimal valor = new BigDecimal(texto.strip());
            return valor.signum() > 0 ? valor : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public boolean configurado() {
        return entradaPorMilhao != null && saidaPorMilhao != null;
    }

    public BigDecimal custo(long tokensEntrada, long tokensSaida) {
        if (!configurado()) {
            throw new IllegalStateException("preço do modelo não configurado");
        }
        return entradaPorMilhao.multiply(BigDecimal.valueOf(tokensEntrada))
                .add(saidaPorMilhao.multiply(BigDecimal.valueOf(tokensSaida)))
                .divide(MILHAO, 6, RoundingMode.HALF_UP);
    }
}
