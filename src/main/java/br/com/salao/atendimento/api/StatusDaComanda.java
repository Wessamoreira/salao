package br.com.salao.atendimento.api;

/**
 * RT-ATD-001 — em que ponto a conta está.
 *
 * <p>Três estados, e a passagem entre eles é de mão única: aberta vira fechada, fechada vira
 * estornada. Nada volta a {@code ABERTA} por mudança de status — reabrir é rotina própria
 * (RT-ATD-007) porque desfazer um fechamento mexe em estoque, comissão e razão.
 */
public enum StatusDaComanda {

    /** Recebendo itens. É o único estado em que a comanda aceita alteração. */
    ABERTA,

    /** Paga e consolidada. Os valores viraram snapshot e não são mais recalculados. */
    FECHADA,

    /** Desfeita depois de fechada, por lançamento de sinal contrário — nunca por apagar. */
    ESTORNADA
}
