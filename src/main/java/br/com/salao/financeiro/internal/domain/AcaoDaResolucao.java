package br.com.salao.financeiro.internal.domain;

/**
 * RT-FIN-011 — o que o dono fez com a linha da conferência.
 *
 * <p>Três, e cada uma vale para os alvos onde faz sentido: {@code CORRIGIR} no par divergente e no
 * pagamento sem venda, {@code ACEITAR} só no par, {@code LANCAR} só na venda sem pagamento.
 */
public enum AcaoDaResolucao {

    /** Usar a versão da adquirente: troca a classificação do pagamento, e lança a diferença de valor. */
    CORRIGIR,

    /** A divergência foi vista e fica como está. Nada muda no dinheiro nem no pagamento. */
    ACEITAR,

    /** Dinheiro que entrou sem comanda: um {@code AJUSTE} positivo no razão. */
    LANCAR
}
