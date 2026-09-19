package br.com.salao.financeiro.internal.domain;

/**
 * RT-FIN-010 — o que difere num par casado.
 *
 * <p>Bandeira e parcelas só contam quando a recepção informou o dado: comparar o "1 parcela" que o sistema
 * assume com o "3x" da adquirente marcaria toda venda parcelada, e um aviso que dispara sempre deixa de
 * ser lido.
 */
public enum Divergencia {
    /** Casaram pelo NSU ou pela autorização, e o valor difere. */
    VALOR,
    /** Débito num lado, crédito no outro — a taxa de cada um é outra. */
    FORMA,
    BANDEIRA,
    PARCELAS,
    /** A venda foi cancelada na adquirente depois de casada: o pagamento existe e o dinheiro não vem. */
    CANCELADA_NA_ADQUIRENTE
}
