package br.com.salao.financeiro.internal.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * RT-FIN-009 — como o arquivo escreve dinheiro.
 *
 * <p>"1.234,56" e "1,234.56" são o mesmo valor, e "1.234" é mil duzentos e trinta e quatro em um e
 * um vírgula dois três quatro no outro. Ler com o formato errado não falha: grava um valor errado. Por
 * isso ele é detectado pelas amostras, mostrado na prévia, e o dono pode trocar.
 */
public enum FormatoDeNumero {

    /** 1.234,56 — ponto separa milhar, vírgula separa centavo. */
    BRASILEIRO,

    /** 1,234.56 — o contrário. */
    INTERNACIONAL;

    /** Milhar em grupos de três, e até quatro casas — a escala da coluna (numeric 19,4). */
    private static final String BRASILEIRO_VALIDO = "-?(\\d{1,3}(\\.\\d{3})+|\\d+)(,\\d{1,4})?";
    private static final String INTERNACIONAL_VALIDO = "-?(\\d{1,3}(,\\d{3})+|\\d+)(\\.\\d{1,4})?";

    /** @return nulo se vazio; lança se não for um número neste formato */
    public BigDecimal ler(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        String t = texto.replace("R$", "").replace(' ', ' ').replace(" ", "").strip();
        boolean negativo = t.startsWith("(") && t.endsWith(")");
        if (negativo) {
            t = "-" + t.substring(1, t.length() - 1);
        }
        // A forma inteira é conferida ANTES de tirar separador. Tirar primeiro fazia "1.234,56" lido
        // como internacional virar 1,23456 — um valor errado, e nenhum erro. O teste pegou.
        String forma = this == BRASILEIRO ? BRASILEIRO_VALIDO : INTERNACIONAL_VALIDO;
        if (!t.matches(forma)) {
            throw new IllegalArgumentException("valor não reconhecido: " + texto.strip());
        }
        t = this == BRASILEIRO ? t.replace(".", "").replace(',', '.') : t.replace(",", "");
        return new BigDecimal(t);
    }

    /**
     * Vota pelas amostras. Na dúvida, brasileiro: o arquivo é de um portal brasileiro, e o caso
     * ambíguo ("100") dá o mesmo valor nos dois.
     */
    public static FormatoDeNumero detectar(List<String> amostras) {
        int brasileiro = 0;
        int internacional = 0;
        for (String amostra : amostras) {
            if (amostra == null) {
                continue;
            }
            String t = amostra.replace("R$", "").replace(' ', ' ').replace(" ", "").strip();
            if (t.matches(".*,\\d{1,2}") || t.matches("-?\\d{1,3}(\\.\\d{3})+")) {
                brasileiro++;
            } else if (t.matches("[^,]*\\.\\d{1,2}") || t.matches("-?\\d{1,3}(,\\d{3})+")) {
                internacional++;
            }
        }
        return internacional > brasileiro ? INTERNACIONAL : BRASILEIRO;
    }
}
