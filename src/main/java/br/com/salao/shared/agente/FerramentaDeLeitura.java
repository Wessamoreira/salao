package br.com.salao.shared.agente;

import tools.jackson.databind.JsonNode;

/**
 * RT-CNV-004 — ferramenta que só consulta.
 *
 * <p>O orquestrador a executa em nome do usuário efetivo, dentro de uma transação somente leitura, e
 * devolve o resultado ao modelo. Uma "leitura" que tente gravar é recusada pelo banco: nem a
 * declaração da própria ferramenta é confiada.
 */
public non-sealed interface FerramentaDeLeitura extends Ferramenta {

    /**
     * Executa, já dentro do contexto de segurança do usuário efetivo.
     *
     * <p>Argumentos vêm do modelo, que é entrada NÃO confiável: validar é trabalho de quem implementa,
     * como seria numa rota HTTP — {@link ArgumentosDaFerramenta} existe para isso.
     */
    JsonNode executar(JsonNode argumentos);
}
