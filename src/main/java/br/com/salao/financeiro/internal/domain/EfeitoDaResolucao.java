package br.com.salao.financeiro.internal.domain;

import java.math.BigDecimal;

/**
 * RT-FIN-011 — o que a resolução mudou, guardado por extenso.
 *
 * <p>Existe por duas razões, e as duas pedem o valor <strong>anterior</strong>:
 *
 * <ul>
 *   <li><strong>Reabrir</strong> (RN-FIN-036) devolve o pagamento ao que ele era. Recalcular a partir
 *       do par não serve: entre a resolução e a reabertura o pagamento pode ter sido tocado por outra
 *       coisa, e o que se desfaz é o que esta resolução fez, não o que hoje pareceria certo.
 *   <li><strong>A trilha</strong> responde "o que mudou?" sem consultar mais nada, inclusive depois de
 *       reaberta.
 * </ul>
 *
 * <p>Os campos {@code *De} são o estado anterior; os {@code *Para}, o novo. Nulo em ambos significa que
 * a resolução não tocou naquele campo — e a reabertura não toca nele também.
 *
 * @param lancado quanto foi escrito no razão, quando foi. Positivo entra no caixa
 */
public record EfeitoDaResolucao(
        String formaDe, String formaPara,
        String bandeiraDe, String bandeiraPara,
        Integer parcelasDe, Integer parcelasPara,
        String nsuDe, String autorizacaoDe,
        String statusDe, String statusPara,
        BigDecimal lancado) {

    public static final EfeitoDaResolucao NENHUM =
            new EfeitoDaResolucao(null, null, null, null, null, null, null, null, null, null, null);

    /** Só o dinheiro: é o efeito de lançar uma venda sem par no caixa. */
    public static EfeitoDaResolucao lancamento(BigDecimal valor) {
        return new EfeitoDaResolucao(null, null, null, null, null, null, null, null, null, null,
                valor);
    }

    /** Se a reabertura precisa mexer no pagamento. Falso quando a resolução só escreveu no razão. */
    public boolean mudouPagamento() {
        return statusDe != null;
    }
}
