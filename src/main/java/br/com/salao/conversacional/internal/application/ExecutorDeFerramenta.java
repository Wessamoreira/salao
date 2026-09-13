package br.com.salao.conversacional.internal.application;

import br.com.salao.shared.agente.Ferramenta;
import tools.jackson.databind.JsonNode;

/**
 * RT-CNV-004 — executar uma ferramenta EM NOME DO usuário efetivo.
 *
 * <p>A implementação real passa por {@code UsuarioEfetivoApi.emNomeDe} — é o que faz o
 * {@code @PreAuthorize} do caso de uso valer. Separado do orquestrador para que as regras dele sejam
 * testáveis sem contexto de segurança.
 */
@FunctionalInterface
public interface ExecutorDeFerramenta {

    JsonNode executar(Ferramenta ferramenta, JsonNode argumentos);
}
