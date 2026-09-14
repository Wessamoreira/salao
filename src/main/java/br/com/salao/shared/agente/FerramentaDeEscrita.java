package br.com.salao.shared.agente;

import java.time.Instant;
import java.util.Optional;
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
 *
 * <h2>Quando alguém de fora da conversa precisa aceitar — RT-CNV-006</h2>
 *
 * <p>A simulação pode dizer que a gravação depende de uma terceira pessoa: a profissional, para um
 * horário fora da jornada dela. {@link Simulacao#aceite()} diz quem e por quê. O sistema pede o aceite
 * DEPOIS da confirmação de quem pediu, e só então chama {@link #confirmar} — com a
 * {@linkplain AceiteRegistrado prova} do aceite. Sem a prova, a ferramenta recusa gravar o que exigia
 * aceite.
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
     * Executa o comando confirmado, no contexto do usuário efetivo resolvido AGORA e dentro da
     * transação que registra a decisão: as duas coisas commitam juntas, ou nenhuma.
     *
     * <p>Recusa sai como {@code ErroDeDominio} com mensagem legível — ela chega às pessoas.
     *
     * @param intencaoId a intenção confirmada, para o rastro
     * @param comando    o {@link Simulacao#comando()} gravado na simulação, sem mudança
     * @param aceite     a prova do aceite, quando a simulação o pediu; vazio quando não pediu
     */
    Efetivacao confirmar(UUID intencaoId, JsonNode comando, Optional<AceiteRegistrado> aceite);

    /**
     * @param resumo  o que a pessoa lê para decidir. Frase do servidor, com nomes do cadastro e
     *                horário no fuso do salão — é o que ela confirma
     * @param comando o que será executado se ela confirmar
     * @param aceite  quem, além dela, precisa aceitar antes de gravar; {@code null} quando ninguém
     */
    record Simulacao(String resumo, JsonNode comando, Aceite aceite) {

        public Simulacao(String resumo, JsonNode comando) {
            this(resumo, comando, null);
        }
    }

    /**
     * RT-CNV-006 — alguém de fora da conversa precisa aceitar.
     *
     * @param nome     como a pessoa é chamada, para os textos
     * @param telefone E.164, só dígitos: para onde vai o pedido, e o único número cuja resposta vale
     * @param motivo   por que precisa aceitar, em texto de gente ("fora da jornada")
     */
    record Aceite(String nome, String telefone, String motivo) {
    }

    /** RT-CNV-006 — quem aceitou, por qual número, quando. */
    record AceiteRegistrado(String nome, String telefone, Instant em) {
    }

    /**
     * @param mensagem a resposta às pessoas
     * @param registro o que ficou gravado, para o rastro
     */
    record Efetivacao(String mensagem, JsonNode registro) {
    }
}
