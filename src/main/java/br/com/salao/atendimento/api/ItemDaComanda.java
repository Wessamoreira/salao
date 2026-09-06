package br.com.salao.atendimento.api;

import br.com.salao.shared.dinheiro.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * RT-ATD-002 — uma linha da comanda.
 *
 * <p>{@code descricao} e {@code precoUnitario} são <strong>snapshot</strong> do momento da venda,
 * não uma consulta ao catálogo (RN-ATD-010). O serviço pode ser renomeado ou reprecificado amanhã;
 * a linha continua dizendo o que foi vendido e por quanto.
 *
 * @param agendamentoId de qual horário marcado veio este item, se veio de algum. É aqui que a
 *                      relação com a agenda vive — na comanda ela seria mentira já no segundo
 *                      item (RN-ATD-004)
 */
public record ItemDaComanda(
        UUID id,
        TipoDeItem tipo,
        UUID servicoId,
        UUID produtoId,
        UUID agendamentoId,
        String descricao,
        BigDecimal quantidade,
        Money precoUnitario,
        Money desconto,
        Money valorTotal,
        List<ExecutanteDoItem> executantes) {
}
