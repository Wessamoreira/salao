package br.com.salao.atendimento.api;

import br.com.salao.shared.dinheiro.Money;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * RT-ATD-002 — quem executou, e quanto disso é dele.
 *
 * <p>{@code percentualComissao} vem em pontos percentuais ({@code 45.00} é 45%), como em
 * {@code ProfissionalResumo} — trocar de forma no meio do caminho é como se erra por cem.
 *
 * @param percentualComissao <strong>snapshot</strong> da regra aplicada; nunca recalculado
 * @param origem             qual nível da cascata venceu, para a auditoria da comissão
 * @param baseCalculo        sobre quanto o percentual incidiu. Derivado enquanto a comanda está
 *                           aberta — o desconto (RT-ATD-004) muda a base — e congelado no
 *                           fechamento
 */
public record ExecutanteDoItem(
        UUID id,
        UUID profissionalId,
        PapelDoExecutante papel,
        BigDecimal percentualComissao,
        OrigemDaComissao origem,
        Money baseCalculo,
        Money valorComissao) {
}
