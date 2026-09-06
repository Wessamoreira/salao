package br.com.salao.financeiro.api;

import br.com.salao.shared.dinheiro.Money;

/**
 * RT-FIN-001 — o que se informa ao registrar um pagamento.
 *
 * @param parcelas    só faz sentido em crédito; nulo vira 1
 * @param nsu         número sequencial único da maquininha, para a conciliação casar
 * @param autorizacao código de autorização do adquirente, idem
 */
public record DadosDoPagamento(
        FormaDePagamento forma,
        Money valor,
        String bandeira,
        Integer parcelas,
        String nsu,
        String autorizacao) {

    /** O caso simples do balcão: dinheiro ou pix, sem dado de cartão. */
    public static DadosDoPagamento de(FormaDePagamento forma, Money valor) {
        return new DadosDoPagamento(forma, valor, null, null, null, null);
    }
}
