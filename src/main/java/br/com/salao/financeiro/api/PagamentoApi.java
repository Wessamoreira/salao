package br.com.salao.financeiro.api;

import br.com.salao.shared.dinheiro.Money;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * RT-FIN-001 — contrato público do módulo {@code financeiro}.
 *
 * <h2>Este módulo não conhece a comanda</h2>
 *
 * <p>{@code comandaId} é um id de <strong>origem</strong>, opaco: o financeiro guarda pagamentos
 * atrelados a ele e sabe somá-los. Não sabe se aquela comanda existe, se está aberta, nem quanto
 * ela deve.
 *
 * <p>Não é preguiça — é a direção da dependência, e ela precisa ser esta. Fechar comanda
 * (RT-ATD-006) é transacional em estoque, pagamento e comissão, e quem orquestra isso é
 * {@code atendimento}: ele <em>tem</em> de poder perguntar "quanto já foi pago?". Se o financeiro
 * também dependesse de {@code atendimento} para validar a comanda, o Spring Modulith reprovaria o
 * build por ciclo — e a descoberta viria só lá na frente, com o fechamento meio escrito.
 *
 * <p>Quem sabe o total da comanda é quem a possui, e é lá que mora a regra de "não pode passar do
 * que falta".
 */
public interface PagamentoApi {

    PagamentoResumo registrar(UUID comandaId, DadosDoPagamento dados);

    List<PagamentoResumo> daOrigem(UUID comandaId);

    /** Zero quando não há pagamento nenhum — somar nada dá nada, e nulo aqui só criaria if. */
    Money totalPago(UUID comandaId);

    Optional<PagamentoResumo> porId(UUID pagamentoId);

    /** Desfaz um pagamento digitado errado. Devolve {@code false} se ele já não existia. */
    boolean remover(UUID pagamentoId);
}
