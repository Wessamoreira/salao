package br.com.salao.notificacao.api;

/**
 * RT-NOT-001 — em que pé está a mensagem.
 *
 * <p>{@code BLOQUEADA} é o estado que mais informa e o mais fácil de não modelar: a mensagem foi
 * pedida e <strong>não</strong> saiu porque a cliente não autorizou aquela finalidade. Não é falha
 * — o sistema fez o certo — e não é envio. Sem esse estado, a recusa viraria uma linha ausente, e
 * ninguém consegue explicar depois por que a cliente não recebeu.
 */
public enum StatusDaMensagem {
    PENDENTE,
    ENVIADA,
    /** O provedor recusou depois de todas as tentativas. */
    FALHOU,
    /** Consentimento negado ou revogado. Não é falha: é a LGPD funcionando. */
    BLOQUEADA
}
