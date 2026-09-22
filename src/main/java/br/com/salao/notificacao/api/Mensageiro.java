package br.com.salao.notificacao.api;

/**
 * RT-NOT-001 — quem de fato entrega a mensagem.
 *
 * <p>Port, e o único ponto do módulo que fala com o mundo. Trocar WhatsApp por outro provedor é
 * trocar a implementação; nada em template, fila, retry ou opt-out muda.
 *
 * <p><strong>Falhar é normal aqui.</strong> Provedor fora do ar, número inválido, limite de taxa —
 * tudo isso vira exceção, e o despachante trata como "tenta de novo mais tarde". Uma implementação
 * que engolisse o erro e devolvesse sucesso quebraria o retry inteiro, porque a mensagem seria
 * marcada como entregue sem ter saído.
 */
public interface Mensageiro {

    /**
     * Se este mensageiro entrega de verdade.
     *
     * <p>Sem valor padrão, e de propósito: toda implementação precisa responder. Um padrão
     * {@code true} deixaria o próximo mensageiro de mentira passar por entrega sem ninguém decidir
     * nada — que é exatamente o defeito que esta pergunta existe para impedir.
     *
     * <p>Falso faz o despachante gravar {@code SEM_PROVEDOR} e nem chamar {@link #enviar}. É a mesma
     * pergunta que o {@code EnviadorDoWhatsApp} da fila da equipe já fazia (RT-CNV-006).
     */
    boolean configurado();

    /**
     * @param destino telefone em E.164 ou e-mail, conforme o canal
     * @throws FalhaDeEnvio quando não foi possível entregar agora
     */
    void enviar(CanalDeEnvio canal, String destino, String texto);

    /** A falha que o despachante sabe tratar. */
    class FalhaDeEnvio extends RuntimeException {
        public FalhaDeEnvio(String mensagem) {
            super(mensagem);
        }

        public FalhaDeEnvio(String mensagem, Throwable causa) {
            super(mensagem, causa);
        }
    }
}
