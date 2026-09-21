package br.com.salao.estoque.internal.domain;

import br.com.salao.shared.dinheiro.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;

/**
 * RT-EST-010 — a conta que sugere o preço (RN-EST-030 a 032).
 *
 * <h2>O chão é invariante, não checagem</h2>
 *
 * <p>Nenhum caminho daqui produz preço abaixo de {@code custo médio × (1 + margem mínima)}. Não há um
 * {@code if} no fim conferindo: todo motivo parte do chão ou de um valor acima dele, e a régua só
 * arredonda para cima. {@code MotorDePrecoTest.nunca_abaixo_do_chao} varre a combinação inteira.
 *
 * <h2>Sem custo médio não há sugestão</h2>
 *
 * <p>Produto sem nada em estoque não tem custo médio (RT-EST-003) — e um chão sobre custo desconhecido
 * seria um chão inventado. A tela prefere não falar sobre esse produto a falar um número sem base.
 *
 * <h2>A conta é reproduzível no papel</h2>
 *
 * <p>É o que o dono precisa para discordar. A IA escreve a frase que explica; o número é daqui, e
 * qualquer pessoa refaz com uma calculadora.
 */
public final class MotorDePreco {

    private static final BigDecimal CEM = new BigDecimal("100");

    private MotorDePreco() {
    }

    /**
     * O produto como o motor precisa vê-lo.
     *
     * @param custoMedio    nulo quando não há estoque — e aí não há sugestão
     * @param precoAtual    nulo é cadastro sem preço de venda, que é um dos motivos de sugerir
     * @param diasSemVenda  desde a última venda; desde o primeiro movimento quando nunca vendeu. Nulo
     *                      quando o produto nunca se moveu
     */
    public record Produto(UUID id, String nome, Money custoMedio, Money precoAtual,
                          BigDecimal saldo, Integer diasSemVenda) {
    }

    public record Sugestao(UUID produtoId, MotivoDaSugestao motivo, Money precoAtual,
                           Money custoMedio, Money precoSugerido, BigDecimal margemAtual,
                           BigDecimal margemSugerida, String racional) {
    }

    /** Decisão do dono: 60 dias parado, com 3 ou mais em estoque. */
    public static final int DIAS_PARA_ENCALHAR = 60;
    public static final BigDecimal SALDO_PARA_ENCALHAR = new BigDecimal("3");

    /** Decisão do dono: "margem muito acima" é o dobro da mínima. */
    public static final BigDecimal FATOR_DA_MARGEM_ALTA = new BigDecimal("2");

    /**
     * @param margemMinima em forma percentual: {@code 30} é 30%
     */
    public static Optional<Sugestao> avaliar(Produto produto, BigDecimal margemMinima) {
        if (produto.custoMedio() == null || produto.custoMedio().valor().signum() <= 0) {
            return Optional.empty();
        }
        Money chao = ReguaDePreco.arredondar(comMargem(produto.custoMedio(), margemMinima));
        Money precoAtual = produto.precoAtual();
        BigDecimal margemAtual = precoAtual == null ? null
                : margem(precoAtual, produto.custoMedio());

        if (precoAtual == null || margemAtual.compareTo(margemMinima) < 0) {
            return sugerir(produto, MotivoDaSugestao.MARGEM_ABAIXO_DO_MINIMO, chao, margemAtual,
                    precoAtual == null
                            ? "Sem preço de venda. Custo médio de " + reais(produto.custoMedio())
                                    + " com " + percentual(margemMinima)
                                    + " de margem mínima dá " + reais(chao) + " na régua."
                            : "Custo médio " + reais(produto.custoMedio()) + "; preço "
                                    + reais(precoAtual) + " dá " + percentual(margemAtual)
                                    + " de margem, abaixo dos " + percentual(margemMinima)
                                    + " mínimos.");
        }

        if (encalhado(produto) && chao.menorQue(precoAtual)) {
            return sugerir(produto, MotivoDaSugestao.ENCALHADO, chao, margemAtual,
                    produto.diasSemVenda() + " dias sem vender com "
                            + produto.saldo().stripTrailingZeros().toPlainString()
                            + " em estoque. Baixar até a margem mínima de "
                            + percentual(margemMinima) + " dá " + reais(chao) + " na régua.");
        }

        BigDecimal margemAlta = margemMinima.multiply(FATOR_DA_MARGEM_ALTA);
        if (margemAtual.compareTo(margemAlta) > 0) {
            Money teto = ReguaDePreco.arredondar(comMargem(produto.custoMedio(), margemAlta));
            if (teto.menorQue(precoAtual)) {
                return sugerir(produto, MotivoDaSugestao.MARGEM_ALTA, teto, margemAtual,
                        "Margem de " + percentual(margemAtual) + ", acima do dobro da mínima ("
                                + percentual(margemAlta) + "). Baixar até lá dá " + reais(teto)
                                + " na régua.");
            }
        }

        if (!ReguaDePreco.estaNaRegua(precoAtual)) {
            Money arredondado = ReguaDePreco.arredondar(precoAtual);
            return sugerir(produto, MotivoDaSugestao.PRECO_QUEBRADO, arredondado, margemAtual,
                    "Preço " + reais(precoAtual) + " fora da régua de balcão; "
                            + reais(arredondado) + " se lê de relance, e a margem sobe para "
                            + percentual(margem(arredondado, produto.custoMedio())) + ".");
        }
        return Optional.empty();
    }

    private static Optional<Sugestao> sugerir(Produto produto, MotivoDaSugestao motivo,
                                              Money sugerido, BigDecimal margemAtual,
                                              String racional) {
        return Optional.of(new Sugestao(produto.id(), motivo, produto.precoAtual(),
                produto.custoMedio(), sugerido, margemAtual,
                margem(sugerido, produto.custoMedio()), racional));
    }

    private static boolean encalhado(Produto produto) {
        return produto.diasSemVenda() != null
                && produto.diasSemVenda() >= DIAS_PARA_ENCALHAR
                && produto.saldo().compareTo(SALDO_PARA_ENCALHAR) >= 0;
    }

    /**
     * O chão: o menor preço que respeita a margem, <strong>sem passar pela régua</strong>.
     *
     * <p>A régua é para o que o sistema sugere. Quando é gente que digita o preço (RT-EST-011), o
     * limite é este — exigir a escada de 9,90 de um humano seria a ferramenta mandando no dono.
     */
    public static Money chaoDeMargem(Money custo, BigDecimal margem) {
        return comMargem(custo, margem);
    }

    /** O preço que dá exatamente esta margem sobre o custo. */
    private static Money comMargem(Money custo, BigDecimal margem) {
        return custo.multiplicar(BigDecimal.ONE.add(margem.divide(CEM, 10, RoundingMode.HALF_UP)));
    }

    /** Em forma percentual, sobre o custo: {@code (preço − custo) / custo × 100}. */
    public static BigDecimal margem(Money preco, Money custo) {
        return preco.valor().subtract(custo.valor())
                .divide(custo.valor(), 6, RoundingMode.HALF_UP)
                .multiply(CEM)
                .setScale(4, RoundingMode.HALF_UP);
    }

    private static String reais(Money valor) {
        return "R$ " + valor.valor().setScale(2, RoundingMode.HALF_UP).toPlainString()
                .replace('.', ',');
    }

    private static String percentual(BigDecimal valor) {
        return valor.setScale(1, RoundingMode.HALF_UP).toPlainString().replace('.', ',') + "%";
    }
}
