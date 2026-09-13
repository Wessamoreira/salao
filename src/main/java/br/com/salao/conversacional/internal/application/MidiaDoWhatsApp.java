package br.com.salao.conversacional.internal.application;

/**
 * RT-CNV-003 — baixar a mídia de uma mensagem recebida.
 *
 * <p>Port. O webhook só traz o id da mídia (RN-CNV-005); os bytes moram na Graph API e são lidos com
 * o token do app.
 */
public interface MidiaDoWhatsApp {

    /** Falso sem token de acesso. O processador não tenta baixar — a linha espera. */
    boolean configurado();

    /**
     * @throws FalhaTransitoria quando tentar de novo pode resolver
     * @throws FalhaPermanente  quando não pode
     */
    MidiaBaixada baixar(String midiaId);

    /** @param mimeType o que a Meta DECLAROU. Não decide nada: quem decide o tipo são os bytes. */
    record MidiaBaixada(byte[] conteudo, String mimeType) {
    }
}
