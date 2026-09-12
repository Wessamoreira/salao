package br.com.salao.atendimento.internal.application;

import br.com.salao.atendimento.api.ComprovanteDeAtendimento;
import br.com.salao.shared.dinheiro.Money;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * RT-FIN-008 — o comprovante em texto de largura fixa, para impressora térmica de 80mm.
 *
 * <h2>Por que texto, e não PDF nem comando de impressora</h2>
 *
 * <p>A impressora é do salão e está ligada na máquina do balcão. Mandar bytes de ESC/POS daqui
 * exigiria que o servidor conhecesse o modelo dela, e todo salão tem outro. Texto de largura fixa
 * é o denominador comum: o front manda para a impressora local, e a mesma string serve para a
 * prévia na tela, para o WhatsApp e para copiar e colar.
 *
 * <h2>48 colunas</h2>
 *
 * <p>É a fonte A de uma térmica de 80mm. A conta de dinheiro fica alinhada à direita numa coluna
 * própria: em largura fixa, é o alinhamento que transforma uma lista de valores em coluna
 * somável — sem ele o olho reprocessa cada linha, que é o mesmo motivo de `tnum` na tela.
 *
 * <h2>Truncar sem reticências</h2>
 *
 * <p>"Escova modeladora com fina…" gasta três colunas para dizer que faltou espaço. Em 48 colunas,
 * três caracteres são uma palavra — e quem lê o recibo já sabe o que comprou. O corte é seco.
 *
 * <h2>A frase de "não é documento fiscal" mora aqui</h2>
 *
 * <p>R-UX-16 exige que o comprovante diga o que não é. Deixá-la para quem chama significaria que,
 * num dos três caminhos de saída, ela some — e o recibo passa a ser usado como nota.
 */
public final class TextoDoComprovante {

    /** Fonte A de uma térmica de 80mm. */
    public static final int COLUNAS = 48;

    private static final DateTimeFormatter QUANDO =
            DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm", new Locale("pt", "BR"));

    private TextoDoComprovante() {
    }

    public static String render(ComprovanteDeAtendimento c) {
        StringBuilder t = new StringBuilder();

        t.append("=".repeat(COLUNAS)).append('\n');
        t.append(centro(c.salao().toUpperCase(new Locale("pt", "BR")))).append('\n');
        t.append(centro("Comanda #" + c.numero())).append('\n');
        t.append(centro(QUANDO.format(c.fechadaEm().atZone(ZoneId.of(c.fuso()))))).append('\n');
        t.append("-".repeat(COLUNAS)).append('\n');
        t.append(corta(c.cliente(), COLUNAS)).append('\n');
        t.append("-".repeat(COLUNAS)).append('\n');

        for (var item : c.itens()) {
            t.append(corta(item.descricao(), COLUNAS)).append('\n');
            // A quantidade e o unitário na mesma linha do total: a conferência que a cliente faz
            // é "1 x 170 dá 170", e ela precisa dos três números juntos.
            t.append(colunas("  " + quantidade(item.quantidade()) + " x "
                    + reais(item.precoUnitario()), reais(item.valorTotal()))).append('\n');
            if (!item.desconto().ehZero()) {
                t.append(colunas("  desconto", "-" + reais(item.desconto()))).append('\n');
            }
        }

        t.append("-".repeat(COLUNAS)).append('\n');
        t.append(colunas("Bruto", reais(c.bruto()))).append('\n');
        if (!c.desconto().ehZero()) {
            t.append(colunas("Desconto", "-" + reais(c.desconto()))).append('\n');
        }
        t.append(colunas("TOTAL", reais(c.total()))).append('\n');

        if (!c.pagamentos().isEmpty()) {
            t.append("-".repeat(COLUNAS)).append('\n');
            for (var pago : c.pagamentos()) {
                String forma = pago.parcelas() > 1
                        ? pago.forma() + " " + pago.parcelas() + "x"
                        : pago.forma();
                t.append(colunas(forma, reais(pago.valor()))).append('\n');
            }
        }

        t.append("=".repeat(COLUNAS)).append('\n');
        t.append(centro("NÃO É DOCUMENTO FISCAL")).append('\n');
        return t.toString();
    }

    /**
     * Rótulo à esquerda, valor à direita, preenchimento no meio.
     *
     * <p>A folga é calculada sobre o rótulo JÁ CORTADO. A primeira versão media o original e
     * preenchia com base nele: quando o corte encurtava a string — e `corta` também removia
     * espaços —, a linha fechava antes da borda. O efeito era sutil e caro: os itens alinhavam
     * na coluna 46 e os totais na 48, então a coluna de dinheiro do recibo tinha duas bordas
     * direitas. Nenhum teste pegou, porque o que eu tinha escrito olhava só as linhas de total.
     */
    private static String colunas(String esquerda, String direita) {
        String rotulo = corta(esquerda, Math.max(COLUNAS - direita.length() - 1, 0));
        int folga = COLUNAS - rotulo.length() - direita.length();
        return rotulo + " ".repeat(Math.max(folga, 0)) + direita;
    }

    private static String centro(String texto) {
        String limpo = corta(texto == null ? "" : texto.strip(), COLUNAS);
        int folga = (COLUNAS - limpo.length()) / 2;
        return " ".repeat(Math.max(folga, 0)) + limpo;
    }

    /**
     * Corte seco. Reticências gastariam três colunas para dizer que faltou espaço.
     *
     * <p>{@code stripTrailing}, e não {@code strip}: o espaço à ESQUERDA é o recuo que separa o
     * detalhe do item do rótulo de topo. A primeira versão limpava os dois lados e comia o
     * recuo — "1 x 170,00" e "Bruto" ficavam na mesma coluna, e a hierarquia do recibo
     * desaparecia.
     */
    private static String corta(String texto, int limite) {
        String seguro = texto == null ? "" : texto.stripTrailing();
        return seguro.length() <= limite ? seguro : seguro.substring(0, limite);
    }

    /** Sem "R$": em 48 colunas, dois caracteres por linha são um item a mais que cabe. */
    private static String reais(Money valor) {
        return String.format(new Locale("pt", "BR"), "%,.2f", valor.valor());
    }

    /** Quantidade inteira sai sem casas: "1 x", não "1,000 x". */
    private static String quantidade(BigDecimal quantidade) {
        return quantidade.stripTrailingZeros().scale() <= 0
                ? quantidade.stripTrailingZeros().toPlainString()
                : quantidade.stripTrailingZeros().toPlainString().replace('.', ',');
    }
}
