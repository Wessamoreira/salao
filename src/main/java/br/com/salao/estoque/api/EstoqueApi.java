package br.com.salao.estoque.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * RT-EST-004 — contrato de saída de estoque.
 *
 * <h2>Não há rota HTTP para isto, e é de propósito</h2>
 *
 * <p>Baixa não é ação de usuário: é <strong>consequência</strong> de fechar uma comanda
 * (RT-ATD-006) ou de executar um serviço com ficha técnica (RT-EST-007). Uma rota que tirasse
 * unidades do estoque diretamente seria um caminho para o saldo diminuir sem que nada explicasse
 * por quê — e é exatamente esse caminho que RT-EST-005 fecha, exigindo motivo para perda e ajuste.
 *
 * <p>É a diferença para RT-ARQ-001, onde a falta de porta HTTP era uma pendência: lá o caso de uso
 * <em>era</em> a ação; aqui ele é o efeito de outra.
 */
public interface EstoqueApi {

    /**
     * Tira {@code quantidade} do produto, do lote que vence primeiro para o que vence por último.
     *
     * @param tipo       {@code VENDA} ou {@code CONSUMO_INTERNO} — o que classifica a saída
     * @param origemTipo e {@code origemId} dizem de onde veio a ordem: item de comanda, serviço
     * @throws br.com.salao.shared.erro.ErroDeDominio se não houver saldo suficiente
     */
    BaixaDeEstoque baixar(UUID produtoId, BigDecimal quantidade, TipoDeMovimento tipo,
                          String origemTipo, UUID origemId);

    /**
     * RT-ATD-007 — devolve ao estoque tudo o que saiu por uma origem, <strong>nos lotes de
     * onde saiu</strong>.
     *
     * <p>Não é "somar de volta a quantidade": as unidades saíram de lotes específicos, com custos e
     * validades específicos, e devolvê-las a um lote genérico faria o custo e o FEFO mentirem a
     * partir dali.
     *
     * <p><strong>Idempotente por construção.</strong> Ela olha o saldo líquido daquela origem em
     * cada lote e só devolve o que ainda está negativo — chamar duas vezes não devolve duas vezes.
     * Isso importa porque quem chama são rotinas que mexem em dinheiro fechado, e repetir uma
     * chamada é o tipo de coisa que acontece.
     *
     * @return quantas unidades voltaram, somadas
     */
    java.math.BigDecimal devolver(String origemTipo, UUID origemId);

    /**
     * RT-EST-007 — baixa o que a ficha técnica do serviço manda, na medida do que existe.
     *
     * <p>É o que faz {@code CONSUMO_INTERNO} deixar de ser um tipo sem quem o escreva: o
     * fechamento da comanda chama isto para cada item de serviço, e a ficha diz quanto de tinta e
     * de ox aquele serviço gasta.
     *
     * <p><strong>Falta de insumo não é erro.</strong> O serviço já foi executado; recusar o
     * fechamento por causa de uma inferência de estoque pararia o balcão com a cliente na cadeira,
     * por motivo contábil. Baixa-se o que há e o resultado diz o que faltou. É diferente da venda
     * de produto, onde alguém lançou o item e vender o que não existe é erro de operação.
     *
     * <p>Serviço sem ficha devolve lista vazia — e não ter ficha é o caso normal de corte, escova
     * e manicure.
     *
     * @param origemTipo e {@code origemId} amarram a baixa ao item de comanda, o que faz a
     *                   reabertura devolver o consumo sozinha (RN-ATD-022)
     */
    ConsumoDeServico consumirPorServico(UUID servicoId, String origemTipo, UUID origemId);
}
