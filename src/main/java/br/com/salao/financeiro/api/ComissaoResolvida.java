package br.com.salao.financeiro.api;

import java.math.BigDecimal;

/**
 * RT-FIN-002 — o percentual que vale, e de onde ele veio.
 *
 * <p>As duas coisas juntas de propósito: gravar só o número deixaria a comanda sem resposta para a
 * pergunta que o profissional faz depois. É o par que RN-ATD-010 manda congelar no executante.
 */
public record ComissaoResolvida(BigDecimal percentual, OrigemDaComissao origem) {

    /** Ninguém configurou nada. Zero, e dizendo que é por falta de regra. */
    public static ComissaoResolvida semRegra() {
        return new ComissaoResolvida(BigDecimal.ZERO, OrigemDaComissao.SEM_REGRA);
    }
}
