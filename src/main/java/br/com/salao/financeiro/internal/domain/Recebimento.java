package br.com.salao.financeiro.internal.domain;

import br.com.salao.shared.dinheiro.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RT-FIN-012 — quando cada parcela cai, e com quanto (RN-FIN-038).
 *
 * <h2>Parcela a parcela</h2>
 *
 * <p>Decisão do dono: sem antecipação, a adquirente paga a primeira parcela no prazo da faixa e as
 * seguintes de 30 em 30. O intervalo é calendário da adquirente, e não escolha do salão — por isso é
 * constante com nome, e não configuração.
 *
 * <h2>Toda parcela conta a partir do dia da venda</h2>
 *
 * <p>E não da parcela anterior. A diferença aparece quando a primeira cai num sábado: ela é empurrada
 * para segunda, e contar a segunda parcela a partir da segunda-feira empurraria todas as seguintes
 * junto — dois dias de erro na primeira viram duas semanas na décima segunda.
 *
 * <h2>A soma das parcelas é exatamente o valor da venda</h2>
 *
 * <p>R$ 100,00 em 3x não é R$ 33,33 três vezes. {@link Money#ratearProporcionalmente} resolve com a
 * última parcela absorvendo a diferença — a mesma regra do rateio de desconto, pela mesma razão: o dono
 * confere somando à mão.
 */
public final class Recebimento {

    /** Dias entre uma parcela e a seguinte, no calendário da adquirente. */
    public static final int DIAS_ENTRE_PARCELAS = 30;

    private Recebimento() {
    }

    /** @param quando dia útil bancário; a data crua cai para a frente quando não é (RN-FIN-038) */
    public record Parcela(int numero, LocalDate quando, Money bruto, Money taxa, Money liquido) {
    }

    /**
     * O cronograma de uma venda no cartão, a partir do que foi <strong>fotografado</strong> nela.
     *
     * <p>Recebe a taxa total já calculada, em vez do contrato: é o que faz a tela de recebíveis mostrar
     * o que foi prometido na venda, e não o que o contrato de hoje diria.
     *
     * @param diaDaVenda      o dia do salão em que o cartão passou
     * @param diasParaReceber prazo da primeira parcela, como estava contratado na venda
     */
    public static List<Parcela> cronograma(Money valorDaVenda, Money taxaTotal, int parcelas,
                                           LocalDate diaDaVenda, int diasParaReceber) {
        int quantas = Math.max(parcelas, 1);
        List<Money> pesos = Collections.nCopies(quantas, Money.de(1));
        List<Money> brutos = valorDaVenda.ratearProporcionalmente(pesos);
        List<Money> taxas = taxaTotal.ratearProporcionalmente(pesos);

        List<Parcela> cronograma = new ArrayList<>(quantas);
        for (int i = 0; i < quantas; i++) {
            LocalDate crua = diaDaVenda
                    .plusDays((long) diasParaReceber + (long) i * DIAS_ENTRE_PARCELAS);
            Money bruto = brutos.get(i);
            Money taxa = taxas.get(i);
            cronograma.add(new Parcela(i + 1, CalendarioBancario.proximoDiaUtil(crua), bruto, taxa,
                    bruto.subtrair(taxa)));
        }
        return List.copyOf(cronograma);
    }

    /**
     * O que vai ser fotografado no pagamento: quanto a adquirente retém no total, quando começa a
     * cair, e com que prazo — para o cronograma poder ser remontado depois sem consultar o contrato.
     */
    public record Previsao(Money taxaTotal, LocalDate primeiraParcela, int diasParaReceber) {
    }

    public static Previsao prever(Money valorDaVenda, int parcelas, LocalDate diaDaVenda,
                                  TaxaContratada taxa) {
        Money total = taxa.sobre(valorDaVenda);
        var primeira = cronograma(valorDaVenda, total, parcelas, diaDaVenda,
                taxa.diasParaReceber()).getFirst();
        return new Previsao(total, primeira.quando(), taxa.diasParaReceber());
    }
}
