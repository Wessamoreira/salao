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
