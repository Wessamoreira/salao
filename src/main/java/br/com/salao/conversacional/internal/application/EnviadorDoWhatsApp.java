package br.com.salao.conversacional.internal.application;

import java.util.List;

/**
 * RT-CNV-006 — mandar mensagem pelo número do salão, para quem trabalha nele.
 *
 * <h2>Port no conversacional, e não o {@code Mensageiro} da notificação</h2>
 *
 * <p>ADR-0006 pôs o adaptador da Cloud API atrás de um port do módulo {@code notificacao}. Na hora de
 * escrever, isso não fecha: o número do salão ({@code canal_whatsapp}) mora aqui, e um adaptador lá
 * precisaria ler daqui — enquanto este módulo precisaria mandar por lá. É dependência circular, e o
 * build a recusa. O dono do número é o dono do envio; a notificação da cliente, quando tiver provedor,
 * pode usar este mesmo adaptador por um {@code Mensageiro} implementado aqui.
 *
 * <h2>Falhar é normal</h2>
 *
 * <p>{@link FalhaTransitoria} é "tenta de novo mais tarde"; {@link FalhaPermanente} é "não adianta" —
 * número que não existe, modelo não aprovado, fora da janela de 24h sem modelo. Engolir o erro e
 * devolver sucesso quebraria a fila inteira.
 */
public interface EnviadorDoWhatsApp {

    /** Falso sem credencial: a fila espera sem gastar tentativa, como o STT e o modelo. */
    boolean configurado();

    /**
     * @return o id da mensagem no provedor, para o rastro
     * @throws FalhaTransitoria quando tentar de novo pode resolver
     * @throws FalhaPermanente  quando não pode
     */
    String enviar(Envio envio);

    /**
     * @param phoneNumberId     o número do salão que manda
     * @param destino           E.164, só dígitos
     * @param botoes            vazio para texto; até três, com id e título
     * @param iniciadaPeloSalao {@code true} quando quem recebe pode não ter escrito nas últimas 24
     *                          horas — o pedido de aceite à profissional. Fora da janela, a Cloud
     *                          API só entrega modelo aprovado, e é o adaptador quem sabe qual
     */
    record Envio(String phoneNumberId, String destino, String texto, List<Botao> botoes,
                 boolean iniciadaPeloSalao) {

        public Envio {
            botoes = List.copyOf(botoes);
        }
    }

    record Botao(String id, String titulo) {
    }
}
