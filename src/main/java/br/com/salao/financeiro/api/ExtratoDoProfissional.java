package br.com.salao.financeiro.api;

import br.com.salao.shared.dinheiro.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * RT-FIN-004 — o que uma pessoa tem a receber no período, item a item.
 *
 * <h2>O sinal aqui é invertido, e é de propósito</h2>
 *
 * <p>No razão, comissão é <strong>negativa</strong>: ela sai do salão. Na conta da pessoa, ela é o
 * que <em>entra</em>. O extrato inverte o sinal uma vez, aqui, para que ninguém precise lembrar
 * disso ao ler a tela.
 *
 * @param aReceber soma do período já invertida. Positivo é o salão devendo
 * @param linhas   uma por lançamento, na ordem em que aconteceram — é o "item a item" que a rotina
 *                 promete, e o que permite conferir contra a comanda
 */
public record ExtratoDoProfissional(
        UUID profissionalId,
        String nome,
        LocalDate de,
        LocalDate ate,
        Money aReceber,
        List<LinhaDoExtrato> linhas) {

    /**
     * @param valor    já invertido: comissão aparece positiva, estorno negativo
     * @param descricao carrega o item e o percentual com a origem da regra (RT-FIN-003) — é o que
     *                  responde "por que 30%?" sem consultar regra nenhuma
     */
    public record LinhaDoExtrato(
            LocalDate data,
            TipoDeLancamento tipo,
            String descricao,
            Money valor,
            UUID comandaId) {
    }
}
