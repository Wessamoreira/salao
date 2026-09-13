package br.com.salao.conversacional.internal.application;

import br.com.salao.conversacional.api.MensagemDeClienteRecebida;
import br.com.salao.conversacional.api.TipoDeMensagem;
import br.com.salao.conversacional.internal.infra.TranscricoesJdbc;
import br.com.salao.shared.tempo.Relogio;
import org.springframework.modulith.events.ApplicationModuleListener;

/**
 * RT-CNV-003 — o primeiro consumidor de {@link MensagemDeClienteRecebida}.
 *
 * <h2>Só enfileira, e é o ponto</h2>
 *
 * <p>{@code @ApplicationModuleListener} abre transação antes do corpo, e isso pega uma conexão do
 * pool. Baixar e transcrever aqui dentro seguraria essa conexão por dezenas de segundos a cada áudio
 * (R-18). O trabalho de rede fica com {@code ProcessadorDeAudios}, fora de transação; este método
 * grava uma linha e sai.
 *
 * <h2>Ao menos uma vez</h2>
 *
 * <p>Sendo o primeiro consumidor, é daqui em diante que o outbox guarda a publicação e o
 * {@code ReenviadorDeEventos} a repete se falhar (a pendência de RT-CNV-001). Repetir é inofensivo:
 * a fila tem uma linha por mensagem, e o {@code on conflict} absorve o segundo aviso.
 */
public class EnfileiradorDeAudios {

    private final TranscricoesJdbc transcricoes;
    private final Relogio relogio;

    public EnfileiradorDeAudios(TranscricoesJdbc transcricoes, Relogio relogio) {
        this.transcricoes = transcricoes;
        this.relogio = relogio;
    }

    @ApplicationModuleListener
    public void aoReceber(MensagemDeClienteRecebida evento) {
        if (evento.tipo() != TipoDeMensagem.AUDIO) {
            return;
        }
        transcricoes.enfileirar(evento.estabelecimentoId(), evento.mensagemId(), relogio.agora());
    }
}
