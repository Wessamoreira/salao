package br.com.salao.financeiro.api;

import br.com.salao.shared.dinheiro.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * RT-FIN-005 — um adiantamento ao profissional.
 *
 * <p>Sem o nome de quem recebeu, de propósito: o nome é do módulo {@code equipe}, e quem o compõe
 * é a borda — a mesma forma de {@code AgendamentoResumo}, que também não carrega o nome do cliente.
 *
 * @param valor            sempre positivo. O sinal que reduz o que a pessoa tem a receber é
 *                         decidido no razão (RN-FIN-012), não aqui
 * @param dataCompetencia  o dia do salão em que o dinheiro saiu, não o do servidor
 * @param cancelado        derivado do razão, não de coluna: um vale cancelado tem um {@code
 *                         ESTORNO} apontando para si (RN-FIN-013)
 */
public record Vale(
        UUID id,
        UUID profissionalId,
        Money valor,
        FormaDoVale forma,
        LocalDate dataCompetencia,
        String motivo,
        boolean cancelado,
        Instant criadoEm) {
}
