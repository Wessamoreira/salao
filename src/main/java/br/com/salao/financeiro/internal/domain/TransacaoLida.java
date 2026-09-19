package br.com.salao.financeiro.internal.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * RT-FIN-009 — uma venda como a adquirente a descreve, já independente de onde veio (ADR-0007).
 *
 * <p>É o que o leitor de arquivo produz hoje e o que um adapter da API de conciliação produziria
 * amanhã: daqui para dentro, ninguém sabe se foi CSV.
 *
 * @param capturadaEm   hora local do salão, como o portal mostra; a conversão para instante é na borda
 * @param horaInformada falso quando o arquivo só trouxe a data — o casamento (RT-FIN-010) não pode
 *                      usar a janela de tempo de uma hora que não existe
 * @param produto       nulo quando a coluna não foi mapeada
 * @param valorLiquido  nulo quando a coluna não foi mapeada
 */
public record TransacaoLida(
        String idNaAdquirente,
        LocalDateTime capturadaEm,
        boolean horaInformada,
        ProdutoDoCartao produto,
        String bandeira,
        int parcelas,
        BigDecimal valorBruto,
        BigDecimal valorLiquido,
        String nsu,
        String autorizacao,
        SituacaoDaTransacao situacao) {
}
