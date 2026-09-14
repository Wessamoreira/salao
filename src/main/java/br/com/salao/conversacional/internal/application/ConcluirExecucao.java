package br.com.salao.conversacional.internal.application;

import br.com.salao.conversacional.internal.infra.ExecucoesJdbc;
import br.com.salao.conversacional.internal.infra.IntencoesJdbc;
import br.com.salao.conversacional.internal.infra.MensagensParaEquipeJdbc;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CNV-006 — encerrar a conversa E pôr a resposta na saída, no mesmo commit.
 *
 * <p>Separados, uma queda entre os dois deixaria a conversa encerrada e ninguém avisado — e nenhuma
 * rodada seguinte a encontraria de novo, porque ela já não está pendente. Juntos, a conversa que volta
 * à fila depois de uma queda ainda não foi encerrada, e o índice único da saída impede a resposta em
 * dobro.
 *
 * <h2>O pedido de confirmação sai DEPOIS da resposta, e só daqui</h2>
 *
 * <p>A intenção nasce no meio da conversa (RT-CNV-005), antes de o modelo escrever o "preparei, confirme
 * pelo código". Enfileirar o pedido ali o faria chegar antes da resposta. E conversa que não terminou —
 * parou no teto, falhou — não entrega pedido de confirmação a quem não recebeu resposta.
 *
 * <p>Conversa RECUSADA não passa por aqui: recusa não responde nada a ninguém (RN-CNV-012).
 */
public class ConcluirExecucao {

    private final ExecucoesJdbc execucoes;
    private final MensagensParaEquipeJdbc saida;
    private final IntencoesJdbc intencoes;

    public ConcluirExecucao(ExecucoesJdbc execucoes, MensagensParaEquipeJdbc saida,
                            IntencoesJdbc intencoes) {
        this.execucoes = execucoes;
        this.saida = saida;
        this.intencoes = intencoes;
    }

    @Transactional
    public void conduzida(UUID execucaoId, String telefone, UUID usuarioEfetivoId,
                          Orquestrador.Resultado resultado, Instant agora) {
        if (execucoes.concluir(execucaoId, usuarioEfetivoId, resultado, agora) != 1) {
            // Outra instância encerrou antes: a resposta é dela.
            return;
        }
        saida.enfileirar(MensagensParaEquipeJdbc.Motivo.RESPOSTA, execucaoId, telefone,
                resultado.resposta(), List.of(), agora);
        intencoes.pendenteDaExecucao(execucaoId).ifPresent(pedido ->
                saida.enfileirar(MensagensParaEquipeJdbc.Motivo.PEDIDO_DE_CONFIRMACAO, pedido.id(),
                        telefone, pedido.texto(), List.of(), agora));
    }

    @Transactional
    public void semModelo(UUID execucaoId, String telefone, UUID usuarioEfetivoId, String resposta,
                          Instant agora) {
        if (execucoes.concluirSemModelo(execucaoId, usuarioEfetivoId, resposta, agora) != 1) {
            return;
        }
        saida.enfileirar(MensagensParaEquipeJdbc.Motivo.RESPOSTA, execucaoId, telefone, resposta,
                List.of(), agora);
    }
}
