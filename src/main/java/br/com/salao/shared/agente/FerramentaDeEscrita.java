package br.com.salao.shared.agente;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * RT-CNV-005 — ferramenta que grava, e que por isso NÃO tem {@code executar}.
 *
 * <h2>simular → confirmação humana explícita → confirmar</h2>
 *
 * <p>O padrão inegociável do plano, em forma de contrato:
 *
 * <ol>
 *   <li>{@link #simular} é o que o modelo consegue disparar. Confere tudo o que a gravação conferiria
 *       e devolve o {@linkplain Simulacao#resumo() resumo} — escrito por quem implementa, com dados do
 *       cadastro, nunca pelo modelo — e o {@linkplain Simulacao#comando() comando} exato que será
 *       executado. Não grava: roda numa transação somente leitura, e o banco recusaria;
 *   <li>o sistema guarda a intenção e pede à PESSOA que confirme, com um código;
 *   <li>{@link #confirmar} só é chamado quando a própria pessoa responde com o código — reconhecido
 *       por código determinístico, antes e fora do modelo. O teste de arquitetura garante que ninguém
 *       mais o chama.
 * </ol>
 *
 * <p>O que a pessoa confirma é o resumo; o que executa é o comando gravado junto dele. Nenhum dos
 * dois pode ser alterado depois de gravado.
 */
public non-sealed interface FerramentaDeEscrita extends Ferramenta {

    /**
     * Prepara a ação sem gravar nada, já dentro do contexto do usuário efetivo.
     *
     * <p>Argumentos vêm do modelo: entrada NÃO confiável. O comando devolvido é o que será executado,
     * então é aqui que eles viram valores canônicos — ids conferidos, instante exato.
     */
    Simulacao simular(JsonNode argumentos);

    /**
     * Executa o comando que a pessoa confirmou, no contexto do usuário efetivo resolvido AGORA e
     * dentro da transação que registra a confirmação: as duas coisas commitam juntas, ou nenhuma.
     *
     * <p>Recusa sai como {@code ErroDeDominio} com mensagem legível — ela chega à pessoa.
     *
     * @param intencaoId a intenção confirmada, para o rastro
     * @param comando    o {@link Simulacao#comando()} gravado na simulação, sem mudança
     */
    Efetivacao confirmar(UUID intencaoId, JsonNode comando);

    /**
     * @param resumo  o que a pessoa lê para decidir. Frase do servidor, com nomes do cadastro e
     *                horário no fuso do salão — é o que ela confirma
     * @param comando o que será executado se ela confirmar
     */
    record Simulacao(String resumo, JsonNode comando) {
    }

    /**
     * @param mensagem a resposta à pessoa
     * @param registro o que ficou gravado, para o rastro
     */
    record Efetivacao(String mensagem, JsonNode registro) {
    }
}
