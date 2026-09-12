package br.com.salao.estoque.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * RT-EST-007 — uma linha da ficha técnica: quanto de um insumo o serviço consome.
 *
 * <h2>É previsão, não medição</h2>
 *
 * <p>Uma coloração em cabelo curto e uma em cabelo longo não gastam a mesma tinta. A ficha diz o
 * <strong>padrão</strong>, e é ele que sai no fechamento da comanda — com origem
 * {@code ITEM_COMANDA}, para a reabertura reverter sozinha (RN-ATD-022).
 *
 * <p>A diferença entre o padrão e o real acumula, e quem a pega é o inventário (RT-EST-006). Isso
 * não é um buraco escondido: é a razão de o inventário existir, e é por isso que ele veio antes
 * desta rotina.
 *
 * @param quantidade na unidade do produto — gramas de tinta, mililitros de ox
 */
public record ItemDaFichaTecnica(
        UUID id,
        UUID servicoId,
        UUID produtoId,
        String produto,
        UnidadeDeMedida unidade,
        BigDecimal quantidade) {
}
