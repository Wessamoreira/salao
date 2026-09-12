package br.com.salao.estoque.internal.application;

import br.com.salao.estoque.api.ErrosDoEstoque;
import br.com.salao.estoque.api.LoteResumo;
import br.com.salao.estoque.api.TipoDeMovimento;
import br.com.salao.estoque.internal.infra.LotesJdbc;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EST-005 — escrever um movimento num lote específico, com motivo.
 *
 * <h2>Por que num lote, e não por produto</h2>
 *
 * <p>Ajuste e devolução ao fornecedor acontecem sobre unidades <strong>identificadas</strong>: o
 * frasco que quebrou é de um lote, a caixa que volta ao fornecedor é de uma nota. Atribuí-las a um
 * lote escolhido por FEFO faria o custo médio e a validade mentirem a partir dali — é a mesma
 * razão pela qual a devolução de comanda devolve aos lotes de onde saiu (RN-ATD-022).
 *
 * <p>Perda é diferente e por isso não passa por aqui: "sumiram três shampoos" não diz de qual
 * lote, e FEFO é a resposta certa quando ninguém sabe.
 *
 * <h2>O motivo é conferido aqui e no banco</h2>
 *
 * <p>A constraint {@code movimento_com_motivo} (V45) recusa perda, ajuste e devolução ao
 * fornecedor sem observação. Esta checagem existe para a recusa ter frase — o banco garante, a
 * aplicação valida por educação.
 */
public class MovimentoComMotivo {

    private static final int MINIMO_DO_MOTIVO = 3;

    private final LotesJdbc lotes;
    private final Relogio relogio;

    public MovimentoComMotivo(LotesJdbc lotes, Relogio relogio) {
        this.lotes = lotes;
        this.relogio = relogio;
    }

    /**
     * @param quantidade positiva entra, negativa sai — o sinal é o que conta (RN-EST-007)
     * @return o id do movimento escrito
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public UUID noLote(UUID loteId, TipoDeMovimento tipo, BigDecimal quantidade, String motivo) {
        exigirMotivo(motivo);
        if (quantidade == null || quantidade.signum() == 0) {
            // Zero não é movimento, e o banco também recusa (`quantidade <> 0`).
            throw new ErroDeDominio(ErrosDoEstoque.QUANTIDADE_INVALIDA,
                    "a quantidade do movimento não pode ser zero");
        }
        UUID tenant = TenantContext.obrigatorio();

        LoteResumo lote = lotes.porId(loteId)
                .orElseThrow(() -> new ErroDeDominio(ErrosDoEstoque.LOTE_NAO_ENCONTRADO,
                        "lote " + loteId + " não encontrado"));

        /*
         * Trava antes de conferir, e lê o saldo DEPOIS de travar, em dois comandos.
         *
         * É a mesma armadilha de RT-EST-004, e ela morde igual aqui: sem o lock, duas perdas
         * concorrentes leem o mesmo saldo, as duas decidem que cabe, e o lote fica negativo. E com
         * lock e leitura no mesmo comando também não resolve — em READ COMMITTED quem acorda do
         * `for update` reavalia a linha travada, mas a subconsulta do saldo já foi resolvida com o
         * snapshot antigo.
         */
        lotes.travarLotesDoProduto(lote.produtoId());
        // Releitura DEPOIS do lock, em comando separado: é isso que faz o saldo ser o de agora
        // e não o do snapshot de antes de travar.
        BigDecimal atual = lotes.porId(loteId).orElseThrow().quantidade();

        BigDecimal depois = atual.add(quantidade);
        if (depois.signum() < 0) {
            // A mensagem diz quanto HÁ: quem está contando precisa saber se o erro foi na
            // contagem ou no lote que escolheu.
            throw new ErroDeDominio(ErrosDoEstoque.SALDO_INSUFICIENTE,
                    "o lote tem " + atual.stripTrailingZeros().toPlainString()
                            + " e o movimento é de " + quantidade.stripTrailingZeros()
                            .toPlainString());
        }

        return lotes.inserirMovimento(tenant, lote.produtoId(), loteId, tipo, quantidade,
                lote.custoUnitario(), null, null, motivo.strip(), relogio.agora(),
                UsuarioAtual.idOuNulo());
    }

    /**
     * Três caracteres, e não "não vazio".
     *
     * <p>Um campo obrigatório que aceita "x" é um campo obrigatório que a recepção preenche com
     * "x" — e aí a coluna existe, a constraint passa, e "por que faltaram três shampoos?" continua
     * sem resposta. Três não resolve o problema; resolve o contorno mais barato dele.
     */
    void exigirMotivo(String motivo) {
        if (motivo == null || motivo.strip().length() < MINIMO_DO_MOTIVO) {
            throw new ErroDeDominio(ErrosDoEstoque.MOTIVO_OBRIGATORIO,
                    "descreva o motivo em pelo menos " + MINIMO_DO_MOTIVO + " caracteres");
        }
    }
}
