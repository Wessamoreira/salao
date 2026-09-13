package br.com.salao.iam.api;

import java.util.function.Function;

/**
 * RT-CNV-002 — em nome de quem o agente conversacional age.
 *
 * <p>docs/05: <em>o bot não é usuário privilegiado; ele age em nome de um usuário resolvido pelo
 * telefone, herdando exatamente as permissões dele</em>. Esta interface é essa frase em código.
 *
 * <h2>Resolvido na hora da ação, nunca guardado</h2>
 *
 * <p>Não há como guardar um {@link UsuarioEfetivo} e usá-lo depois: {@link #emNomeDe} recebe o
 * TELEFONE e resolve de novo no instante de executar. É a mesma razão de o JWT carregar o perfil e
 * não a lista de permissões (RT-IAM-006) — uma recepcionista desativada entre a mensagem e a ação
 * não pode ver a ação executada com as permissões de antes.
 *
 * <h2>Telefone, e não {@code usuarioId}</h2>
 *
 * <p>Receber um id deixaria quem chama escolher em nome de quem agir. O telefone vem de uma
 * mensagem assinada pela Meta (RN-CNV-001), e é a própria Meta que atesta o remetente: o agente só
 * consegue agir como quem de fato escreveu.
 */
public interface UsuarioEfetivoApi {

    /**
     * Quem é este telefone no salão em escopo. Só consulta — não abre contexto de segurança.
     *
     * @param telefone em E.164, só dígitos, como chega do WhatsApp
     */
    Identificacao identificarPorTelefone(String telefone);

    /**
     * Executa {@code acao} com as permissões do usuário efetivo deste telefone.
     *
     * <p>Todo {@code @PreAuthorize} dos casos de uso vale como valeria para a própria pessoa — com o
     * teto do canal. Ao sair, o contexto anterior é restaurado, inclusive quando a ação falha.
     *
     * @throws br.com.salao.shared.erro.ErroDeDominio {@code ER-IAM-CONTATO_NAO_IDENTIFICADO} quando
     *         o telefone não resolve para um usuário efetivo — um código só para todos os motivos
     * @throws IllegalStateException quando chamado com uma pessoa já autenticada no contexto
     */
    <T> T emNomeDe(String telefone, Function<UsuarioEfetivo, T> acao);
}
