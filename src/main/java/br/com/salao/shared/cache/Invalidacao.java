package br.com.salao.shared.cache;

import java.util.UUID;

/**
 * RT-INF-007 — o que trafega no canal de invalidação.
 *
 * <p>Só identificadores. O payload do {@code NOTIFY} é limitado a 8000 bytes e, mais importante,
 * atravessa estabelecimentos no mesmo canal — vale aqui a mesma regra do outbox (RN-INF-009):
 * carrega ID, nunca conteúdo.
 *
 * @param chave {@code null} significa "todo o cache deste estabelecimento"
 */
public record Invalidacao(UUID estabelecimentoId, String cache, String chave) {

    public static Invalidacao deChave(UUID estabelecimentoId, String cache, String chave) {
        return new Invalidacao(estabelecimentoId, cache, chave);
    }

    public static Invalidacao deCacheInteiro(UUID estabelecimentoId, String cache) {
        return new Invalidacao(estabelecimentoId, cache, null);
    }
}
