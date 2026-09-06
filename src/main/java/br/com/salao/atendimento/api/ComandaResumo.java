package br.com.salao.atendimento.api;

import br.com.salao.shared.dinheiro.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * RT-ATD-001 — a comanda como as telas e os outros módulos a veem.
 *
 * <p><strong>Sem o nome da cliente</strong>, pela mesma razão de {@code AgendamentoResumo}: quem
 * precisa exibir busca na {@code ClienteApi}, e o que não é carregado não vaza em log. A barra de
 * comandas abertas pede os nomes em lote, numa consulta só.
 *
 * <p>Os três valores são a soma <strong>gravada</strong> dos itens, recalculada a cada mudança
 * enquanto a comanda está aberta e congelada no fechamento. Somar os itens a cada leitura seria
 * dar resposta diferente depois que o preço do catálogo mudasse.
 */
public record ComandaResumo(
        UUID id,
        UUID clienteId,
        long numero,
        StatusDaComanda status,
        OrigemDaComanda origem,
        Instant abertaEm,
        UUID abertaPor,
        Instant fechadaEm,
        Money valorBruto,
        Money valorDesconto,
        Money valorLiquido,
        int versao) {
}
