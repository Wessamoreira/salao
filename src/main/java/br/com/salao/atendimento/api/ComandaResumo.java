package br.com.salao.atendimento.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * RT-ATD-001 — a comanda como as telas e os outros módulos a veem.
 *
 * <p><strong>Sem o nome da cliente</strong>, pela mesma razão de {@code AgendamentoResumo}: quem
 * precisa exibir busca na {@code ClienteApi}, e o que não é carregado não vaza em log. A barra de
 * comandas abertas pede os nomes em lote, numa consulta só.
 *
 * <p>Os três valores são zero enquanto não há item (RT-ATD-002). Estão aqui desde já porque é a
 * soma <em>gravada</em> que o fechamento congela — o contrato da tela não muda quando os itens
 * chegarem.
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
        BigDecimal valorBruto,
        BigDecimal valorDesconto,
        BigDecimal valorLiquido,
        int versao) {
}
