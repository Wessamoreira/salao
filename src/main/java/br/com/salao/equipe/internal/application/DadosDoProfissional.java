package br.com.salao.equipe.internal.application;

import br.com.salao.equipe.api.TipoDeVinculo;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * RT-EQP-001 — o que se sabe do profissional ao cadastrar.
 *
 * @param comissaoPercentual em pontos percentuais: {@code 45} é 45%
 * @param contratoAssinadoEm data da assinatura do contrato escrito, anulável mesmo para parceria
 */
public record DadosDoProfissional(
        String nome,
        String apelido,
        String documento,
        TipoDeVinculo vinculo,
        String telefone,
        String email,
        BigDecimal comissaoPercentual,
        LocalDate contratoInicio,
        LocalDate contratoFim,
        LocalDate contratoAssinadoEm) {
}
