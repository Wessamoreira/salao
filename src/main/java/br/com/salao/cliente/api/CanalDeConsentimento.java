package br.com.salao.cliente.api;

/**
 * RT-CLI-004 — onde a decisão da cliente foi coletada.
 *
 * <p>É o que se apresenta quando alguém pergunta "onde está a prova de que ela aceitou?". Sem o
 * canal, o registro afirma que houve consentimento sem dizer como — que é o mesmo que não ter
 * registro nenhum diante de um questionamento.
 */
public enum CanalDeConsentimento {

    /** Perguntado no balcão, com a cliente presente. O caso mais comum no salão. */
    BALCAO,
    WHATSAPP,
    TELEFONE,
    SITE,
    /** Ficha de papel assinada, arquivada no salão. */
    FICHA_PAPEL,
    /**
     * Veio de um sistema anterior.
     *
     * <p>Existe para ser honesto sobre a origem: uma decisão importada tem valor probatório menor
     * que uma coletada aqui, e apagar essa diferença faria o sistema afirmar com a mesma
     * segurança duas coisas bem diferentes.
     */
    IMPORTACAO
}
