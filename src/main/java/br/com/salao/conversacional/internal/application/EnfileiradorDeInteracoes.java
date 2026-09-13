package br.com.salao.conversacional.internal.application;

import br.com.salao.conversacional.api.MensagemDeClienteRecebida;
import br.com.salao.conversacional.api.TipoDeMensagem;
import br.com.salao.conversacional.internal.infra.ExecucoesJdbc;
import br.com.salao.conversacional.internal.infra.MensagensRecebidasJdbc;
import br.com.salao.shared.tempo.Relogio;
import org.springframework.modulith.events.ApplicationModuleListener;

/**
 * RT-CNV-004 — mensagem de TEXTO vira conversa na fila.
 *
 * <p>Só enfileira, pelo mesmo motivo do enfileirador de áudio (RN-CNV-006). Áudio não passa por aqui:
 * vira conversa quando a transcrição termina ({@link ConcluirTranscricao}), porque até lá não há texto
 * para o agente ler.
 */
public class EnfileiradorDeInteracoes {

    private final MensagensRecebidasJdbc mensagens;
    private final ExecucoesJdbc execucoes;
    private final Relogio relogio;

    public EnfileiradorDeInteracoes(MensagensRecebidasJdbc mensagens, ExecucoesJdbc execucoes,
                                    Relogio relogio) {
        this.mensagens = mensagens;
        this.execucoes = execucoes;
        this.relogio = relogio;
    }

    @ApplicationModuleListener
    public void aoReceber(MensagemDeClienteRecebida evento) {
        if (evento.tipo() != TipoDeMensagem.TEXTO) {
            return;
        }
        mensagens.remetente(evento.mensagemId())
                .filter(r -> r.texto() != null && !r.texto().isBlank())
                .ifPresent(r -> execucoes.enfileirar(evento.estabelecimentoId(),
                        evento.mensagemId(), r.telefone(), r.texto(), relogio.agora()));
    }
}
