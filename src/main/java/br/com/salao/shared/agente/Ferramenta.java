package br.com.salao.shared.agente;

import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * RT-CNV-004 — uma ação que o agente conversacional pode pedir.
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
 * <h2>O contrato é sobre o que o agente PODE, e não sobre o que ele sabe</h2>
 *
 * <ul>
 *   <li>{@link #permissoesExigidas()} — TODAS precisam estar no usuário efetivo para a ferramenta
 *       ser oferecida. Vazio significa "nunca oferecida": ferramenta que não declara o que exige é
 *       tratada como perigosa, e não como livre;
 *   <li>{@link #natureza()} — {@link Natureza#ESCRITA} nunca é executada pelo orquestrador. Escrita
 *       passa por simular → confirmação humana → confirmar (RT-CNV-005/006).
 * </ul>
 *
 * <p>A declaração de permissões é a PRIMEIRA tranca, e pode estar errada. A segunda é o
 * {@code @PreAuthorize} do caso de uso que {@link #executar} chama — que roda dentro do contexto do
 * usuário efetivo e recusa mesmo quando a declaração mentiu.
 */
public interface Ferramenta {

    /** {@code [a-zA-Z0-9_-]{1,64}} — o formato que os provedores de LLM aceitam. */
    String nome();

    /** Para o modelo. Diz o que a ferramenta faz; nunca contém instrução de segurança. */
    String descricao();

    /** JSON Schema dos argumentos. */
    JsonNode esquemaDeEntrada();

    Set<String> permissoesExigidas();

    Natureza natureza();

    /**
     * Executa, já dentro do contexto de segurança do usuário efetivo.
     *
     * <p>Argumentos vêm do modelo, que é entrada NÃO confiável: validar é trabalho de quem implementa,
     * como seria numa rota HTTP.
     */
    JsonNode executar(JsonNode argumentos);

    enum Natureza {
        LEITURA,
        ESCRITA
    }
}
