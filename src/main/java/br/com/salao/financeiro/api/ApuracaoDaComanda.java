package br.com.salao.financeiro.api;

import br.com.salao.shared.dinheiro.Money;
import java.util.List;
import java.util.UUID;

/**
 * RT-FIN-003 — o que uma comanda fechada gerou, do ponto de vista do razão.
 *
 * <p>Quem monta isto é o {@code atendimento}, que tem os itens; quem decide em que linhas isso vira
 * é o {@code financeiro}. A divisão importa: o atendimento não sabe que comissão entra com sinal
 * negativo nem a que mês a linha pertence, e não deveria saber.
 *
 * @param comissoes uma por executante. Vazia é normal: comanda só de produto não paga comissão
 *                  enquanto produto não entrar na cascata (RN-ATD-018)
 */
public record ApuracaoDaComanda(
        UUID comandaId,
        long numero,
        Money receitaDeServico,
        Money receitaDeProduto,
        List<ComissaoAApurar> comissoes) {

    /**
     * @param percentual e {@code origem} vêm do snapshot do executante (RN-ATD-010) e entram na
     *                   descrição da linha: é o que faz o extrato responder "por que 30%?" sem
     *                   consultar regra nenhuma
     */
    public record ComissaoAApurar(
            UUID profissionalId,
            Money valor,
            java.math.BigDecimal percentual,
            OrigemDaComissao origem,
            String descricaoDoItem) {
    }
}
