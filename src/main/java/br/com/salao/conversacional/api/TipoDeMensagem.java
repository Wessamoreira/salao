package br.com.salao.conversacional.api;

/**
 * RT-CNV-001 — o que chegou.
 *
 * <p>Espelha o {@code check} de {@code mensagem_recebida.tipo}. A lista é curta de propósito: são
 * os tipos que o agente sabe tratar. Tudo o mais é {@link #NAO_SUPORTADO}.
 */
public enum TipoDeMensagem {

    TEXTO,
    AUDIO,
    IMAGEM,
    DOCUMENTO,

    /**
     * RT-CNV-006 — o toque num botão que o sistema mandou.
     *
     * <p>Não tem texto: tem o id do botão, que é nosso. É reconhecido, nunca interpretado pelo modelo,
     * e só vale vindo do número para onde o botão foi mandado (RN-CNV-019).
     */
    BOTAO,

    /**
     * O tipo existe no WhatsApp e não é tratado aqui: figurinha, localização, contato, reação,
     * resposta de lista.
     *
     * <p><strong>Guardado, e não descartado.</strong> Descartar tornaria "a cliente mandou e o
     * sistema não respondeu" indistinguível de "a cliente não mandou nada" — e a primeira pergunta
     * do suporte é exatamente essa. Guardado, a mensagem aparece na caixa, sem conteúdo, e alguém
     * pode ler no WhatsApp.
     */
    NAO_SUPORTADO
}
