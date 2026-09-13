package br.com.salao.iam.api;

import java.util.Set;
import java.util.UUID;

/**
 * RT-CNV-002 — a pessoa em nome de quem o agente age, com o que ela pode fazer POR ESTE CANAL.
 *
 * <p>{@code permissoes} <strong>não é</strong> {@code MapaDePermissoes.de(perfil)}: é esse conjunto
 * com o teto do canal aplicado. Quem precisar saber o que o agente pode fazer pergunta aqui, nunca
 * ao perfil.
 */
public record UsuarioEfetivo(UUID usuarioId, String nome, Perfil perfil, Set<String> permissoes) {

    public UsuarioEfetivo {
        permissoes = Set.copyOf(permissoes);
    }

    public boolean pode(String permissao) {
        return permissoes.contains(permissao);
    }
}
