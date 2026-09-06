package br.com.salao.atendimento.api;

import java.util.List;

/**
 * RT-ATD-002 — a comanda com o que há dentro dela.
 *
 * <p>Separada de {@link ComandaResumo} de propósito: a barra do balcão mostra trinta comandas e
 * precisa de número, cliente e total; carregar os itens de todas para desenhar uma barra seria
 * pagar pela tela de fechamento em toda tela do sistema.
 */
public record ComandaDetalhada(ComandaResumo comanda, List<ItemDaComanda> itens) {
}
