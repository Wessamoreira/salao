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
 * @param saldoAnterior o acumulado de <strong>tudo</strong> antes de {@code de}, positivo quando o
 *                      salão já devia. Existe desde RT-FIN-006: quem adiantou mais do que ganhou
 *                      atravessa o mês devendo, e um extrato que só olhasse o período mostraria um
 *                      "a receber" maior do que o repasse — que é a conversa que ele existe para
 *                      encerrar, voltando pela terceira porta
 * @param aReceber soma do período já invertida. Positivo é o salão devendo
 * @param totais   o mesmo número aberto por tipo — desde RT-FIN-005 o líquido mistura o que foi
 *                 ganho com o que já foi adiantado, e um número só não distingue os dois
 * @param linhas   uma por lançamento, na ordem em que aconteceram — é o "item a item" que a rotina
 *                 promete, e o que permite conferir contra a comanda
 */
public record ExtratoDoProfissional(
        UUID profissionalId,
        String nome,
        LocalDate de,
        LocalDate ate,
        Money saldoAnterior,
        Money aReceber,
        List<TotalPorTipo> totais,
        List<LinhaDoExtrato> linhas) {

    /**
     * A posição acumulada da pessoa ao fim do período. Pode ser negativa quando ela adiantou mais
     * do que ganhou; o fechamento aplica o piso em zero somente ao calcular o repasse.
     */
    public Money saldoFinal() {
        return saldoAnterior.somar(aReceber);
    }

    /**
     * RT-FIN-005 — quanto do líquido veio de cada tipo, já com o sinal do extrato.
     *
     * <p>Existe porque o vale muda a natureza do total: até RT-FIN-004, {@code aReceber} era
     * "quanto a pessoa ganhou"; agora é "quanto ela ganhou menos o que já levou". Sem a abertura,
     * quem recebeu R$ 600 e adiantou R$ 550 lê "R$ 50" e conclui que trabalhou por R$ 50.
     *
     * <p>Lista e não mapa: a ordem é a dos tipos, estável, e um {@code Map} com chave de enum
     * chegaria ao JSON em ordem arbitrária.
     */
    public record TotalPorTipo(TipoDeLancamento tipo, Money valor) {
    }

    /**
     * @param valor      já invertido: comissão aparece positiva, vale e estorno negativos
     * @param descricao  carrega o item e o percentual com a origem da regra (RT-FIN-003) — é o que
     *                   responde "por que 30%?" sem consultar regra nenhuma
     * @param origemTipo de onde a linha veio: {@code COMANDA}, {@code VALE}, {@code FECHAMENTO}.
     *                   Este par se chamava {@code comandaId} até RT-FIN-004, quando comanda era a
     *                   única origem possível — e o vale tornou o nome mentira. Um id de vale
     *                   guardado num campo chamado "comanda" é o tipo de coisa que ninguém percebe
     *                   até a tela abrir a comanda errada
     */
    public record LinhaDoExtrato(
            LocalDate data,
            TipoDeLancamento tipo,
            String descricao,
            Money valor,
            String origemTipo,
            UUID origemId) {
    }
}
