package br.com.salao.financeiro.internal.application;

import br.com.salao.financeiro.api.FormaDePagamento;
import br.com.salao.financeiro.internal.domain.FaixaDeTaxa;
import br.com.salao.financeiro.internal.domain.Recebimento;
import br.com.salao.financeiro.internal.infra.TaxasDoAdquirenteJdbc;
import br.com.salao.shared.dinheiro.Money;

/**
 * RT-FIN-012 — o que o sistema promete sobre um pagamento no cartão: quanto a adquirente retém e
 * quando o dinheiro começa a cair (RN-FIN-037, RN-FIN-038).
 *
 * <h2>Sem contrato, sem promessa</h2>
 *
 * <p>Faixa não cadastrada devolve {@code null}, e o pagamento nasce sem previsão — que é a verdade. Um
 * percentual chutado viraria número na tela do dono, e número na tela do dono vira decisão.
 *
 * <h2>Sem {@code @PreAuthorize}</h2>
 *
 * <p>Prever é consequência de registrar pagamento, e não ação de usuário. Quem autoriza é quem tem a
 * ação — a mesma forma de {@code Razao} e de {@code Competencia}.
 */
public class PrevisaoDeRecebimento {

    private final TaxasDoAdquirenteJdbc taxas;
    private final Competencia competencia;

    public PrevisaoDeRecebimento(TaxasDoAdquirenteJdbc taxas, Competencia competencia) {
        this.taxas = taxas;
        this.competencia = competencia;
    }

    /** {@code null} quando o pagamento não passa por adquirente, ou a faixa não tem contrato. */
    public Recebimento.Previsao para(FormaDePagamento forma, Money valor, int parcelas) {
        FaixaDeTaxa faixa = FaixaDeTaxa.de(forma, parcelas);
        if (faixa == null) {
            return null;
        }
        var contratada = taxas.contratadas().get(faixa);
        if (contratada == null) {
            return null;
        }
        // O dia do salão, e não o do servidor: a venda das 23h50 em Manaus é de hoje, e o prazo
        // conta a partir dela (RN-FIN-010).
        return Recebimento.prever(valor, parcelas, competencia.hoje(), contratada);
    }
}
