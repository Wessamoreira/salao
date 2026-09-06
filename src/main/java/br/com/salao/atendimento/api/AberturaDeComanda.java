package br.com.salao.atendimento.api;

/**
 * RT-ATD-001 — o que abrir uma comanda devolve.
 *
 * <p>{@code jaEstavaAberta} existe porque abrir é <strong>idempotente por cliente</strong>: pedir
 * de novo devolve a que já existe, em vez de criar a segunda. Sem esta bandeira a recepção não
 * teria como saber a diferença, e a tela diria "comanda 42 aberta" para uma comanda que está
 * aberta desde as nove da manhã — com itens dentro.
 *
 * <p>É a mesma escolha de {@code SerieCriada.naoCouberam} (RT-AGD-009): o desfecho que não é o
 * esperado vem na resposta, não escondido.
 */
public record AberturaDeComanda(ComandaResumo comanda, boolean jaEstavaAberta) {
}
