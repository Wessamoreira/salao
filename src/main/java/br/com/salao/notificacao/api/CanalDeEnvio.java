package br.com.salao.notificacao.api;

/**
 * RT-NOT-001 — por onde a mensagem sai.
 *
 * <p>Três, e o WhatsApp é o que importa: é onde a cliente de salão de fato lê. SMS existe como
 * queda para quem não tem WhatsApp, e e-mail para o que precisa de anexo — nenhum salão manda
 * lembrete por e-mail e espera ser lido.
 */
public enum CanalDeEnvio {
    WHATSAPP,
    SMS,
    EMAIL
}
