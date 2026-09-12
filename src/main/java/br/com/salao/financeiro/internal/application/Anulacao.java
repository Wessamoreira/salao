package br.com.salao.financeiro.internal.application;

import br.com.salao.financeiro.api.Lancamento;
import br.com.salao.financeiro.api.TipoDeLancamento;
import br.com.salao.financeiro.internal.infra.LancamentosJdbc;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tempo.Relogio;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-007 — anular uma linha do razão na competência dela.
 *
 * <h2>Por que a competência aqui é a da linha original, e em {@code Razao.estornarOrigem} é hoje</h2>
 *
 * <p>São dois atos diferentes, e a diferença não é detalhe:
 *
 * <ul>
 *   <li><strong>Reabrir uma comanda ou cancelar um vale</strong> é um fato de negócio que acontece
 *       HOJE: a cliente voltou, o dinheiro foi devolvido no balcão. A anulação é um evento novo, e
 *       pertence ao dia em que aconteceu — é o que RN-FIN-010 diz, e continua valendo.</li>
 *   <li><strong>Anular uma linha errada</strong> é dizer que ela nunca deveria ter existido. Se a
 *       anulação caísse em outro mês, o mês do erro continuaria errado e outro mês absorveria a
 *       correção — o oposto de corrigir.</li>
 * </ul>
 *
 * <p>E há um caso em que a competência da anulação é <strong>obrigatoriamente</strong> a original:
 * o estorno de um {@code REPASSE}. RN-FIN-017 já dá ao repasse uma competência <em>escolhida</em>
 * (o fim do período que ele liquida). Anulá-lo em outra data deixaria o período liberado mas com a
 * soma acumulada até o fim dele ainda contando o repasse original — e refechar pagaria zero,
 * fazendo a liberação não liberar nada.
 *
 * <p>A regra que unifica os dois: <em>a anulação de uma linha cuja competência foi escolhida
 * herda essa competência</em>.
 */
public class Anulacao {

    private final LancamentosJdbc lancamentos;
    private final Relogio relogio;

    public Anulacao(LancamentosJdbc lancamentos, Relogio relogio) {
        this.lancamentos = lancamentos;
        this.relogio = relogio;
    }

    /**
     * Anula todas as linhas ainda pendentes de uma origem, cada uma na competência dela.
     *
     * <p>Idempotente pelo próprio razão: quem já foi anulado tem uma linha apontando para si e
     * não volta em {@code pendentesDeEstorno} — sem marca para manter em dia.
     *
     * @return quantas linhas foram anuladas; zero significa "já estava"
     */
    @Transactional
    public int deOrigem(UUID tenant, String origemTipo, UUID origemId, String motivo) {
        List<Lancamento> pendentes = lancamentos.pendentesDeEstorno(origemTipo, origemId);
        for (Lancamento original : pendentes) {
            escrever(tenant, original, motivo);
        }
        return pendentes.size();
    }

    /** Anula uma linha específica, na competência dela. */
    @Transactional
    public void deLancamento(UUID tenant, Lancamento original, String motivo) {
        escrever(tenant, original, motivo);
    }

    private void escrever(UUID tenant, Lancamento original, String motivo) {
        Instant agora = relogio.agora();
        lancamentos.inserir(tenant,
                // A competência da ORIGINAL. É este parâmetro que faz refechar um período
                // liberado produzir o número certo.
                original.dataCompetencia(),
                TipoDeLancamento.ESTORNO,
                original.origemTipo(), original.origemId(),
                original.contraparteTipo(), original.contraparteId(),
                original.valor().negado(),
                "Estorno: " + original.descricao() + " — " + motivo,
                original.id(), agora, UsuarioAtual.idOuNulo());
    }
}
