package br.com.salao.financeiro.internal.domain;

import br.com.salao.financeiro.api.FormaDePagamento;

/**
 * RT-FIN-012 — as quatro faixas em que a adquirente cobra (RN-FIN-037).
 *
 * <p>Por produto e faixa de parcela, e não por bandeira: é assim que o contrato do salão é negociado, e
 * a recepção quase nunca informa a bandeira — uma taxa por bandeira ficaria sem saber qual usar na
 * maioria das vendas.
 */
public enum FaixaDeTaxa {

    DEBITO("Débito"),
    CREDITO_A_VISTA("Crédito à vista"),
    CREDITO_2_6("Crédito 2 a 6x"),
    CREDITO_7_12("Crédito 7 a 12x");

    private final String rotulo;

    FaixaDeTaxa(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }

    /**
     * A faixa de um pagamento, ou {@code null} quando ele não passa por adquirente.
     *
     * <p>Acima de 12 parcelas cai em {@code CREDITO_7_12}: o contrato do salão não vai além de 12, e
     * recusar aqui seria inventar um erro numa venda que já aconteceu.
     */
    public static FaixaDeTaxa de(FormaDePagamento forma, int parcelas) {
        if (forma == null || !forma.ehCartao()) {
            return null;
        }
        if (forma == FormaDePagamento.DEBITO) {
            return DEBITO;
        }
        if (parcelas <= 1) {
            return CREDITO_A_VISTA;
        }
        return parcelas <= 6 ? CREDITO_2_6 : CREDITO_7_12;
    }
}
