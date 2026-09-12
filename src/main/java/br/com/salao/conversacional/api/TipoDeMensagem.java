package br.com.salao.conversacional.api;

/**
 * RT-CNV-001 — o que chegou.
 *
 * <p>Espelha o {@code check} de {@code mensagem_recebida.tipo}. A lista é curta de propósito: são
 * os tipos que o agente vai saber tratar (RT-CNV-003 a RT-CNV-005). Tudo o mais é
 * {@link #NAO_SUPORTADO}.
 */
public enum TipoDeMensagem {

    TEXTO,
    AUDIO,
    IMAGEM,
    DOCUMENTO,

    /**
     * O tipo existe no WhatsApp e não é tratado aqui: figurinha, localização, contato, reação,
     * resposta a botão.
     *
     * <p><strong>Guardado, e não descartado.</strong> Descartar tornaria "a cliente mandou e o
     * sistema não respondeu" indistinguível de "a cliente não mandou nada" — e a primeira pergunta
     * do suporte é exatamente essa. Guardado, a mensagem aparece na caixa, sem conteúdo, e alguém
     * pode ler no WhatsApp.
     */
    NAO_SUPORTADO
}
