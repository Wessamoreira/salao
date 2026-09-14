package br.com.salao.shared.agente;

import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * RT-CNV-004 / RT-CNV-005 — uma ação que o agente conversacional pode pedir.
 *
 * <h2>Por que mora em {@code shared}, e não em {@code conversacional}</h2>
 *
 * <p>A defesa em profundidade contra prompt injection depende de a ferramenta chamar o CASO DE USO
 * do módulo dono — com o {@code @PreAuthorize} de lá. Só que a fronteira proíbe {@code conversacional}
 * de chamar caso de uso interno de outro módulo; chamando só a API pública, a segunda tranca some.
 *
 * <p>Então cada módulo contribui as suas ferramentas, como beans, implementando este contrato; o
 * orquestrador as recolhe do contexto. {@code shared} é aberto e não depende de ninguém, e nenhum
 * módulo de negócio passa a depender de {@code conversacional}.
 *
 * <h2>Duas formas, e só duas — RN-CNV-014</h2>
 *
 * <ul>
 *   <li>{@link FerramentaDeLeitura} tem {@code executar};
 *   <li>{@link FerramentaDeEscrita} NÃO tem. Tem {@code simular}, que não grava nada, e
 *       {@code confirmar}, que só a resposta da própria pessoa com o código alcança.
 * </ul>
 *
 * <p>Selada para não existir uma terceira: "escrita com {@code executar}" é o que o padrão inegociável
 * do plano proíbe, e assim ela não compila. Até RT-CNV-004 a escrita era uma {@code Natureza} que o
 * orquestrador recusava num {@code if}; agora não há o que recusar — o orquestrador não tem método
 * que grave.
 *
 * <h2>O contrato é sobre o que o agente PODE, e não sobre o que ele sabe</h2>
 *
 * <p>{@link #permissoesExigidas()} — TODAS precisam estar no usuário efetivo para a ferramenta ser
 * oferecida. Vazio significa "nunca oferecida": ferramenta que não declara o que exige é tratada como
 * perigosa, e não como livre.
 *
 * <p>A declaração é a PRIMEIRA tranca, e pode estar errada. A segunda é o {@code @PreAuthorize} do
 * caso de uso que a ferramenta chama — que roda dentro do contexto do usuário efetivo e recusa mesmo
 * quando a declaração mentiu.
 */
public sealed interface Ferramenta permits FerramentaDeLeitura, FerramentaDeEscrita {

    /** {@code [a-zA-Z0-9_-]{1,64}} — o formato que os provedores de LLM aceitam. */
    String nome();

    /** Para o modelo. Diz o que a ferramenta faz; nunca contém instrução de segurança. */
    String descricao();

    /** JSON Schema dos argumentos. */
    JsonNode esquemaDeEntrada();

    Set<String> permissoesExigidas();
}
