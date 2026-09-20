package br.com.salao.financeiro.internal.domain;

import br.com.salao.financeiro.api.FormaDePagamento;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * RT-FIN-011 — o que "usar a versão da Stone" muda num par divergente (RN-FIN-034).
 *
 * <h2>Classificação muda no pagamento; dinheiro vira lançamento</h2>
 *
 * <p>Forma, bandeira e parcelas são como o salão <em>classificou</em> a venda: corrigi-las não mexe em
 * um centavo do caixa, e é o que faz a taxa (RT-FIN-012) ser calculada sobre o produto certo.
 *
 * <p>Valor é dinheiro recebido, e não se reescreve: a comanda já foi apurada, a comissão saiu em cima
 * dele e o mês pode estar fechado. A diferença sai daqui como {@link #diferenca} e vira um {@code
 * AJUSTE} no razão — positivo quando a adquirente cobrou mais do que o salão registrou.
 *
 * <h2>Cancelada não se corrige</h2>
 *
 * <p>{@link Divergencia#CANCELADA_NA_ADQUIRENTE} não tem versão certa para copiar: a venda deixou de
 * existir. Ela sai em {@link #restantes}, e é por isso que um par só com ela não aceita a correção — o
 * caminho é aceitar como está, com motivo.
 *
 * @param forma     a forma que o pagamento passa a ter, ou nulo quando a forma não diverge
 * @param bandeira  idem para a bandeira
 * @param parcelas  idem para as parcelas
 * @param diferenca quanto lançar no razão, ou nulo quando o valor não diverge. Nunca zero: VALOR só é
 *                  divergência quando os valores diferem
 * @param restantes o que a correção não resolve, e que segue valendo no par
 */
public record Correcao(FormaDePagamento forma, String bandeira, Integer parcelas,
                       BigDecimal diferenca, Set<Divergencia> restantes) {

    /** Se há o que corrigir. Falso num par cuja única divergência é o cancelamento. */
    public boolean mudaAlgo() {
        return forma != null || bandeira != null || parcelas != null || diferenca != null;
    }

    public static Correcao calcular(Collection<Divergencia> divergencias, ProdutoDoCartao produto,
                                    String bandeiraDaVenda, int parcelasDaVenda,
                                    BigDecimal valorDaVenda, BigDecimal valorDoPagamento) {
        var restantes = EnumSet.noneOf(Divergencia.class);
        FormaDePagamento forma = null;
        String bandeira = null;
        Integer parcelas = null;
        BigDecimal diferenca = null;

        for (Divergencia divergencia : divergencias) {
            switch (divergencia) {
                // Venda sem produto, ou "outro" (voucher, pré-pago), não diz a forma — e o Casador nem
                // marca FORMA nesse caso. A guarda é para a correção nunca inventar um crédito.
                case FORMA -> {
                    if (produto == ProdutoDoCartao.CREDITO || produto == ProdutoDoCartao.DEBITO) {
                        forma = produto == ProdutoDoCartao.CREDITO
                                ? FormaDePagamento.CREDITO : FormaDePagamento.DEBITO;
                    } else {
                        restantes.add(Divergencia.FORMA);
                    }
                }
                case BANDEIRA -> bandeira = bandeiraDaVenda;
                case PARCELAS -> parcelas = parcelasDaVenda;
                case VALOR -> diferenca = valorDaVenda.subtract(valorDoPagamento);
                case CANCELADA_NA_ADQUIRENTE -> restantes.add(Divergencia.CANCELADA_NA_ADQUIRENTE);
            }
        }
        return new Correcao(forma, bandeira, parcelas, diferenca,
                Collections.unmodifiableSet(restantes));
    }
}
