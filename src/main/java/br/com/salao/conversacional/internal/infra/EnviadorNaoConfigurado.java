package br.com.salao.conversacional.internal.infra;

import br.com.salao.conversacional.internal.application.EnviadorDoWhatsApp;
import br.com.salao.conversacional.internal.application.FalhaPermanente;

/**
 * RT-CNV-006 — sem provedor de envio, nada sai: as mensagens esperam na fila.
 *
 * <p>Diferente do {@code MensageiroDeRegistro} da notificação, que registra no log e marca como
 * enviada, este não finge. Uma resposta marcada ENVIADA sem ter saído é a pior mentira desta fila: a
 * recepção esperando um "marcado" que o sistema diz ter mandado. Esperando, a fila mostra o que falta
 * — e quando o provedor entrar, entrega o que ficou.
 */
public class EnviadorNaoConfigurado implements EnviadorDoWhatsApp {

    @Override
    public boolean configurado() {
        return false;
    }

    @Override
    public String enviar(Envio envio) {
        throw new FalhaPermanente("nenhum provedor de envio configurado");
    }
}
