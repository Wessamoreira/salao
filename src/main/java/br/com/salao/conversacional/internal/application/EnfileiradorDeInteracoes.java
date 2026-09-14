package br.com.salao.conversacional.internal.application;

import br.com.salao.conversacional.api.MensagemDeClienteRecebida;
import br.com.salao.conversacional.api.TipoDeMensagem;
import br.com.salao.conversacional.internal.infra.ExecucoesJdbc;
import br.com.salao.conversacional.internal.infra.MensagensRecebidasJdbc;
import br.com.salao.shared.tempo.Relogio;
import org.springframework.modulith.events.ApplicationModuleListener;

/**
 * RT-CNV-004 — mensagem de TEXTO vira conversa na fila; RT-CNV-006 — toque de BOTÃO vira decisão.
 *
 * <p>Só enfileira, pelo mesmo motivo do enfileirador de áudio (RN-CNV-006). Áudio não passa por aqui:
 * vira conversa quando a transcrição termina ({@link ConcluirTranscricao}), porque até lá não há texto
 * para o agente ler.
 *
 * <p>O botão entra na MESMA fila, marcado {@code BOTAO}: o processador o decide sem modelo e sem exigir
 * conta de usuário — quem aceita pode ser a profissional, que não tem uma.
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
        if (evento.tipo() != TipoDeMensagem.TEXTO && evento.tipo() != TipoDeMensagem.BOTAO) {
            return;
        }
        mensagens.remetente(evento.mensagemId()).ifPresent(remetente -> {
            if (evento.tipo() == TipoDeMensagem.BOTAO) {
                if (remetente.botaoId() != null) {
                    execucoes.enfileirar(evento.estabelecimentoId(), evento.mensagemId(),
                            remetente.telefone(), remetente.botaoId(), ExecucoesJdbc.Tipo.BOTAO,
                            relogio.agora());
                }
            } else if (remetente.texto() != null && !remetente.texto().isBlank()) {
                execucoes.enfileirar(evento.estabelecimentoId(), evento.mensagemId(),
                        remetente.telefone(), remetente.texto(), ExecucoesJdbc.Tipo.CONVERSA,
                        relogio.agora());
            }
        });
    }
}
