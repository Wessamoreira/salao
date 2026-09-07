package br.com.salao.financeiro.api;

import br.com.salao.shared.dinheiro.Money;
import java.time.LocalDate;
import java.util.UUID;

/**
 * RT-FIN-003 — uma linha do livro-razão.
 *
 * @param dataCompetencia a que <strong>mês</strong> o valor pertence, que não é o dia em que a
 *                        linha foi escrita: a comanda de 31 de março apurada em 1º de abril é de
 *                        março
 * @param valor           o <strong>sinal</strong> define débito e crédito. Receita entra positiva;
 *                        comissão, negativa
 * @param estornaId       quando esta linha anula outra. A original nunca é apagada
 */
public record Lancamento(
        UUID id,
        LocalDate dataCompetencia,
        TipoDeLancamento tipo,
        String origemTipo,
        UUID origemId,
        String contraparteTipo,
        UUID contraparteId,
        Money valor,
        String descricao,
        UUID estornaId) {
}
