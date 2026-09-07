package br.com.salao.financeiro.api;

/**
 * RT-FIN-005 — por onde o dinheiro do adiantamento saiu.
 *
 * <h2>Por que não reusar {@link FormaDePagamento}</h2>
 *
 * <p>Aquele enum descreve dinheiro <strong>entrando</strong>, e quatro dos seus sete valores não
 * têm sentido saindo: não se adianta em crédito parcelado, nem em cortesia, nem em fiado. Reusá-lo
 * obrigaria a recusar quatro valores em tempo de execução — transformando em erro de negócio algo
 * que o tipo já podia impedir na compilação.
 *
 * <p>A distinção que importa aqui é outra: <strong>de onde o dinheiro saiu</strong>. Falta na
 * gaveta ou saiu do banco é a conta que o dono faz no fim do dia, e o razão sozinho não a responde.
 */
public enum FormaDoVale {

    /** Saiu da gaveta. É o que faz o caixa do dia fechar com menos do que o faturamento. */
    DINHEIRO,

    PIX,

    TRANSFERENCIA
}
