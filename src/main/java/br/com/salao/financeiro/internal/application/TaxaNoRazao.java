package br.com.salao.financeiro.internal.application;

import br.com.salao.financeiro.api.RazaoApi;
import br.com.salao.financeiro.api.TipoDeLancamento;
import br.com.salao.financeiro.internal.domain.SituacaoDaTransacao;
import br.com.salao.financeiro.internal.infra.LancamentosJdbc;
import br.com.salao.financeiro.internal.infra.TransacoesDoAdquirenteJdbc;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tenant.TenantContext;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-012 — a taxa <strong>real</strong> da adquirente no razão (RN-FIN-039).
 *
 * <h2>Real, e nunca prevista</h2>
 *
 * <p>O extrato traz o líquido de cada venda; a taxa é {@code bruto − líquido}. A previsão fica no
 * pagamento e na tela — decisão do dono: nenhum número estimado soma no resultado do mês.
 *
 * <h2>Uma vez por venda, sem marca nenhuma</h2>
 *
 * <p>Quem responde "esta venda já teve a taxa lançada?" é o próprio razão, pela origem
 * {@code ADQUIRENTE}. É a mesma forma do vale (RT-FIN-005) e da resolução (RT-FIN-011) — e é o que
 * torna reimportar o mesmo arquivo inofensivo.
 *
 * <h2>A competência é o dia da venda</h2>
 *
 * <p>E não o da importação, ao contrário do ajuste de RT-FIN-011. A diferença é real: lá a correção é
 * uma decisão tomada hoje sobre o que ninguém sabia; aqui a taxa é custo conhecido de uma venda datada.
 * É seguro porque a contraparte é o salão — nenhum repasse já pago muda de valor.
 *
 * <p>Não é caso de uso: não tem porta. Quem chama é a importação, no fim da mesma transação, com
 * {@code financeiro:reconcile} já conferido. {@code MANDATORY} garante que ninguém o chama fora dela.
 */
public class TaxaNoRazao {

    private static final Logger log = LoggerFactory.getLogger(TaxaNoRazao.class);

    static final String ORIGEM = "ADQUIRENTE";

    private final TransacoesDoAdquirenteJdbc transacoes;
    private final LancamentosJdbc lancamentos;
    private final RazaoApi razao;
    private final EstabelecimentoApi estabelecimentos;

    public TaxaNoRazao(TransacoesDoAdquirenteJdbc transacoes, LancamentosJdbc lancamentos,
                       RazaoApi razao, EstabelecimentoApi estabelecimentos) {
        this.transacoes = transacoes;
        this.lancamentos = lancamentos;
        this.razao = razao;
        this.estabelecimentos = estabelecimentos;
    }

    /**
     * @param lancadas   vendas que ganharam a linha de taxa agora
     * @param retido     quanto a adquirente reteve nessas vendas
     * @param estornadas vendas canceladas cuja taxa foi devolvida
     */
    public record Rodada(int lancadas, Money retido, int estornadas) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Rodada lancar(UUID importacaoId, Instant agora) {
        UUID tenant = TenantContext.obrigatorio();
        ZoneId fuso = estabelecimentos.fusoDe(tenant);
        UUID por = UsuarioAtual.idOuNulo();
        int lancadas = 0;
        int estornadas = 0;
        Money retido = Money.ZERO;

        for (var venda : transacoes.daImportacao(importacaoId)) {
            boolean jaLancada = !lancamentos.daOrigem(ORIGEM, venda.id()).isEmpty();

            if (venda.situacao() == SituacaoDaTransacao.CANCELADA) {
                // A adquirente devolve o que reteve. Idempotente pelo próprio razão.
                if (jaLancada && razao.estornarOrigem(ORIGEM, venda.id(),
                        "venda cancelada na adquirente") > 0) {
                    estornadas++;
                }
                continue;
            }
            // Sem líquido no arquivo não há taxa real — e inventar seria pior que não lançar. A
            // coluna é opcional no mapeamento (RT-FIN-009).
            if (jaLancada || venda.valorLiquido() == null) {
                continue;
            }
            Money taxa = new Money(venda.valorBruto()).subtrair(new Money(venda.valorLiquido()));
            // Taxa zero é venda isenta; linha de zero não move saldo e polui o extrato.
            if (taxa.ehZero() || taxa.ehNegativo()) {
                continue;
            }

            lancamentos.inserir(tenant, venda.capturadaEm().atZone(fuso).toLocalDate(),
                    TipoDeLancamento.TAXA_ADQUIRENTE, ORIGEM, venda.id(), "SALAO", null,
                    taxa.negado(), "Taxa da adquirente sobre a venda de "
                            + venda.valorBruto().toPlainString(), null, agora, por);
            lancadas++;
            retido = retido.somar(taxa);
        }

        if (lancadas > 0 || estornadas > 0) {
            log.info("Taxa da adquirente: {} vendas, {} retidos; {} estornadas", lancadas, retido,
                    estornadas);
        }
        return new Rodada(lancadas, retido, estornadas);
    }
}
