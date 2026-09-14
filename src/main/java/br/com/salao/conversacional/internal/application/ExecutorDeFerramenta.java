package br.com.salao.conversacional.internal.application;

import br.com.salao.shared.agente.FerramentaDeEscrita;
import br.com.salao.shared.agente.FerramentaDeLeitura;
import tools.jackson.databind.JsonNode;

/**
 * RT-CNV-004 / RT-CNV-005 — o que o orquestrador consegue fazer com uma ferramenta, EM NOME DO usuário
 * efetivo.
 *
 * <p>A implementação real passa por {@code UsuarioEfetivoApi.emNomeDe} — é o que faz o
 * {@code @PreAuthorize} do caso de uso valer — e por uma transação somente leitura, que faz o banco
 * recusar escrita vinda daqui. Separado do orquestrador para que as regras dele sejam testáveis sem
 * contexto de segurança nem banco.
 *
 * <p><strong>Não há método que confirme.</strong> O orquestrador não tem como gravar: a única porta
 * para {@code FerramentaDeEscrita.confirmar} é a resposta da pessoa com o código.
 */
public interface ExecutorDeFerramenta {

    JsonNode executar(FerramentaDeLeitura ferramenta, JsonNode argumentos);

    /**
     * Simula a escrita e registra a intenção pendente, que a pessoa vai confirmar ou não.
     *
     * @return o resumo que a pessoa vai ler
     */
    String propor(FerramentaDeEscrita ferramenta, JsonNode argumentos);
}
