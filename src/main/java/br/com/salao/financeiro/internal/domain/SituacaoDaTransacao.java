package br.com.salao.financeiro.internal.domain;

/**
 * RT-FIN-009 — a situação da venda na adquirente.
 *
 * <p>Só duas: a venda que não foi aprovada (negada, pré-autorização) nem entra na tabela — é
 * {@link LinhaDoExtrato.Ignorada}. E a mudança só anda para a frente: aprovada vira cancelada; o
 * contrário é recusado, porque dinheiro que volta a existir sem explicação é o tipo de coisa que alguém
 * precisa olhar.
 */
public enum SituacaoDaTransacao {
    APROVADA,
    CANCELADA
}
