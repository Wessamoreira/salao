package br.com.salao.conversacional.internal.application;

import br.com.salao.iam.api.UsuarioEfetivo;

/**
 * RT-CNV-010 — quem está do outro lado da conversa, e são só dois.
 *
 * <h2>Por que um tipo, e não um {@code UsuarioEfetivo} que pode ser nulo</h2>
 *
 * <p>Nulo faria o caminho da visitante ser decidido por um {@code if} na classe mais sensível do
 * sistema, e um {@code if} esquecido devolveria à visitante o catálogo da equipe. Selado, o
 * compilador obriga cada lugar a dizer o que faz com cada um — e um terceiro caso, no dia em que
 * existir, quebra o build em todos eles em vez de cair no ramo do {@code else}.
 *
 * <h2>A visitante não é "sem permissão": é sem identidade</h2>
 *
 * <p>Ela não tem conta, não tem perfil e não entra no mecanismo de permissões (ADR-0011). A
 * conversa dela roda sem {@code SecurityContext} nenhum, e {@code emNomeDe} continua sendo
 * exclusivo da equipe — o único caminho até um {@code @PreAuthorize}.
 */
public sealed interface QuemFala {

    /** RT-CNV-004 — alguém da equipe, com conta, perfil e permissões herdadas pelo agente. */
    record DaEquipe(UsuarioEfetivo usuario) implements QuemFala {
    }

    /**
     * RT-CNV-010 — quem escreveu e não é da equipe.
     *
     * <p>Sem campo nenhum de propósito: o telefone já viaja na execução, e guardá-lo aqui o levaria
     * ao prompt do modelo sem que ninguém tivesse decidido isso.
     */
    record Visitante() implements QuemFala {
    }
}
