package br.com.salao.financeiro.api;

/**
 * RT-FIN-001 — se o pagamento já foi casado com o extrato do adquirente (ADR-0007).
 *
 * <p>Nasce {@code PENDENTE} para cartão e {@code NAO_APLICAVEL} para o resto: dinheiro e pix não
 * têm extrato de adquirente para casar, e deixá-los pendentes encheria a fila da conciliação com
 * linhas que nunca vão sair de lá.
 */
public enum StatusDeConciliacao {

    /** Cartão esperando o extrato. */
    PENDENTE,

    /** Casado com uma transação do adquirente. */
    CONCILIADO,

    /** Casado com divergência de valor ou data — é o que a tela de conciliação mostra. */
    DIVERGENTE,

    /** Não passa por adquirente: dinheiro, pix, transferência, cortesia, fiado. */
    NAO_APLICAVEL
}
