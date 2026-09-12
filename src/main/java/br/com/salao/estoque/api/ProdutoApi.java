package br.com.salao.estoque.api;

import java.util.Optional;
import java.util.UUID;

/**
 * RT-ATD-003 — contrato público do módulo {@code estoque}.
 *
 * <p>Nasce quando o primeiro módulo precisa de produto: {@code atendimento}, para lançar a venda na
 * comanda. Antes disso seria uma interface sem chamador — documentação que o compilador não
 * confere.
 *
 * <p>Devolve o {@link ProdutoResumo} inteiro, e não um "posso vender?" booleano, porque quem
 * chama precisa de <strong>três</strong> respostas diferentes com correções diferentes: o produto
 * não existe, não está à venda, ou está à venda sem preço. Um booleano só faria a comanda dizer
 * "não deu" para os três casos.
 */
public interface ProdutoApi {

    Optional<ProdutoResumo> porId(UUID produtoId);

    /**
     * RT-EST-009 — o produto que o leitor bipou.
     *
     * <p>Existe no contrato público porque quem mais vai usá-la é o {@code atendimento}: bipar na
     * comanda é o caminho mais rápido de lançar um produto, e o `atendimento` não pode consultar a
     * tabela do estoque direto.
     *
     * <p>Devolve vazio para código desconhecido. Quem decide o que dizer é a tela — "não
     * cadastrado, quer cadastrar?" na tela de estoque, e "produto não encontrado" na comanda são
     * respostas diferentes para o mesmo vazio.
     */
    Optional<ProdutoResumo> porCodigoDeBarras(String codigoDeBarras);
}
