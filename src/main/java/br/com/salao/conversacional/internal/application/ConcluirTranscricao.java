package br.com.salao.conversacional.internal.application;

import br.com.salao.conversacional.internal.infra.ExecucoesJdbc;
import br.com.salao.conversacional.internal.infra.MensagensRecebidasJdbc;
import br.com.salao.conversacional.internal.infra.TranscricoesJdbc;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CNV-004 — a transcrição termina E vira conversa, no mesmo commit.
 *
 * <p>Separados, uma queda entre os dois deixaria a transcrição concluída e a cliente sem resposta —
 * sem nada na fila para ninguém retomar, porque a transcrição não é refeita.
 *
 * <p>Áudio mudo (texto vazio) conclui e não conversa: não há o que o agente ler, e chamar o modelo
 * para "…" custaria dinheiro para responder nada.
 */
public class ConcluirTranscricao {

    private final TranscricoesJdbc transcricoes;
    private final MensagensRecebidasJdbc mensagens;
    private final ExecucoesJdbc execucoes;

    public ConcluirTranscricao(TranscricoesJdbc transcricoes, MensagensRecebidasJdbc mensagens,
                               ExecucoesJdbc execucoes) {
        this.transcricoes = transcricoes;
        this.mensagens = mensagens;
        this.execucoes = execucoes;
    }

    @Transactional
    public void executar(UUID transcricaoId, UUID mensagemId, UUID estabelecimentoId,
                         Transcritor.TextoTranscrito resultado, Instant agora) {
        if (transcricoes.concluir(transcricaoId, resultado, agora) == 0) {
            return;
        }
        String texto = resultado.texto();
        if (texto == null || texto.isBlank()) {
            return;
        }
        mensagens.remetente(mensagemId).ifPresent(r ->
                execucoes.enfileirar(estabelecimentoId, mensagemId, r.telefone(), texto,
                        ExecucoesJdbc.Tipo.CONVERSA, agora));
    }
}
