package br.com.salao.financeiro.internal.domain;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * RT-FIN-009 — dinheiro nas mensagens que o dono lê: "R$ 1.234,56", e não "1234.5600".
 *
 * <p>Só para texto. O valor gravado e comparado continua sendo {@link BigDecimal}.
 */
public final class Reais {

    private static final Locale BRASIL = Locale.forLanguageTag("pt-BR");

    private Reais() {
    }

    public static String de(BigDecimal valor) {
        var formato = NumberFormat.getCurrencyInstance(BRASIL);
        // O espaço inquebrável que o Java põe depois do "R$" vira espaço comum: a mensagem vai para
        // tela e para log, e nenhum dos dois ganha nada com ele.
        return formato.format(valor).replace('\u00a0', ' ');
    }
}
