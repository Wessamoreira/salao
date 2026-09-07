package br.com.salao.financeiro.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * RT-FIN-002 — uma linha da cascata.
 *
 * @param percentual em pontos percentuais ({@code 45.00} é 45%), como em toda parte deste projeto
 */
public record RegraDeComissao(
        UUID id,
        EscopoDaRegra escopo,
        UUID profissionalId,
        UUID servicoId,
        BigDecimal percentual) {
}
