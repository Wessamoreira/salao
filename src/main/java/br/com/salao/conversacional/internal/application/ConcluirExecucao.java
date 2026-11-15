package br.com.salao.conversacional.internal.application;

import br.com.salao.conversacional.internal.infra.AcoesDoAgenteJdbc;
import br.com.salao.conversacional.internal.infra.ExecucoesJdbc;
import br.com.salao.conversacional.internal.infra.IntencoesJdbc;
import br.com.salao.conversacional.internal.infra.MensagensParaEquipeJdbc;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CNV-006 / RT-CNV-007 — encerrar a conversa, pôr a resposta na saída e deixar o rastro na trilha
 * do agente, no mesmo commit.
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
 * <p>Conversa RECUSADA não responde nada a ninguém (RN-CNV-012) — mas passa por aqui, porque deixa
 * rastro: a tentativa do número desconhecido é exatamente o que o painel de revisão precisa mostrar.
 *
 * <h2>Toda conversa encerrada vira uma linha de {@code acao_ia} — RN-CNV-022</h2>
 *
 * <p>Concluída, recusada ou que falhou de vez. No mesmo commit do encerramento: separada, uma queda
 * entre as duas deixaria a conversa encerrada sem rastro, e nada a reencontraria. É por isso que todo
 * encerramento do processador passa por esta classe, e nenhum chama {@code ExecucoesJdbc} direto.
 */
public class ConcluirExecucao {

    private final ExecucoesJdbc execucoes;
    private final MensagensParaEquipeJdbc saida;
    private final IntencoesJdbc intencoes;
    private final AcoesDoAgenteJdbc acoes;

    /**
     * A execução que responde, e as que ela absorveu (RT-CNV-009).
     *
     * @param absorvidas vazia quando a rajada foi uma mensagem só, que é o caso comum
     */
    public record Alvo(UUID id, List<UUID> absorvidas) {

        public static Alvo unico(UUID id) {
            return new Alvo(id, List.of());
        }
    }

    public ConcluirExecucao(ExecucoesJdbc execucoes, MensagensParaEquipeJdbc saida,
                            IntencoesJdbc intencoes, AcoesDoAgenteJdbc acoes) {
        this.execucoes = execucoes;
        this.saida = saida;
        this.intencoes = intencoes;
        this.acoes = acoes;
    }

    @Transactional
    public void conduzida(Alvo alvo, String telefone, UUID usuarioEfetivoId,
                          Orquestrador.Resultado resultado, Instant agora) {
        UUID execucaoId = alvo.id();
        if (execucoes.concluir(execucaoId, usuarioEfetivoId, resultado, agora) != 1) {
            // Outra instância encerrou antes: a resposta é dela.
            return;
        }
        execucoes.juntar(alvo.absorvidas(), execucaoId);
        saida.enfileirar(MensagensParaEquipeJdbc.Motivo.RESPOSTA, execucaoId, telefone,
                resultado.resposta(), List.of(), agora);
        intencoes.pendenteDaExecucao(execucaoId).ifPresent(pedido ->
                saida.enfileirar(MensagensParaEquipeJdbc.Motivo.PEDIDO_DE_CONFIRMACAO, pedido.id(),
                        telefone, pedido.texto(), List.of(), agora));
        acoes.registrar(execucaoId, agora);
    }

    @Transactional
    public void semModelo(Alvo alvo, String telefone, UUID usuarioEfetivoId, String resposta,
                          Instant agora) {
        UUID execucaoId = alvo.id();
        if (execucoes.concluirSemModelo(execucaoId, usuarioEfetivoId, resposta, agora) != 1) {
            return;
        }
        execucoes.juntar(alvo.absorvidas(), execucaoId);
        saida.enfileirar(MensagensParaEquipeJdbc.Motivo.RESPOSTA, execucaoId, telefone, resposta,
                List.of(), agora);
        acoes.registrar(execucaoId, agora);
    }

    /**
     * RT-CNV-010 — a conversa de quem não é da equipe.
     *
     * <p>A resposta vai para ELA, e não para a equipe: o destino da saída é o telefone de quem
     * escreveu, como em toda RESPOSTA. Sem {@code usuario_efetivo_id}, porque não houve em nome de
     * quem — e com {@code de_visitante}, para a trilha não confundir isto com alguém da equipe sem
     * nome (V70).
     *
     * <p>Sem pedido de confirmação: não existe intenção pendente no caminho dela.
     */
    @Transactional
    public void deVisitante(Alvo alvo, String telefone, Orquestrador.Resultado resultado,
                            Instant agora) {
        UUID execucaoId = alvo.id();
        if (execucoes.concluirDeVisitante(execucaoId, resultado, agora) != 1) {
            return;
        }
        execucoes.juntar(alvo.absorvidas(), execucaoId);
        saida.enfileirar(MensagensParaEquipeJdbc.Motivo.RESPOSTA, execucaoId, telefone,
                resultado.resposta(), List.of(), agora);
        acoes.registrar(execucaoId, agora);
    }

    /**
     * Recusada sem conversa — identidade, limite, teto. {@code parcial} é o que chegou a acontecer
     * antes do corte, e vai para o rastro também.
     */
    @Transactional
    public void recusada(Alvo alvo, String motivo, UUID usuarioEfetivoId,
                         Orquestrador.Resultado parcial, Instant agora) {
        if (execucoes.recusar(alvo.id(), motivo, usuarioEfetivoId, parcial, agora) == 1) {
            execucoes.juntar(alvo.absorvidas(), alvo.id());
            acoes.registrar(alvo.id(), agora);
        }
    }

    /** Falhou de vez: sem mais tentativas, ou falha que tentar de novo não resolve. */
    @Transactional
    public void falhou(Alvo alvo, String erro, Instant agora) {
        if (execucoes.falhar(alvo.id(), erro) == 1) {
            execucoes.juntar(alvo.absorvidas(), alvo.id());
            acoes.registrar(alvo.id(), agora);
        }
    }
}
