package br.com.salao.shared.cache;

/**
 * RT-INF-007 — a mesma chave para quem guarda e para quem invalida.
 *
 * <p>Registrado como bean {@code chaveDeCache} para uso direto no SpEL:
 *
 * <pre>{@code
 * @Cacheable(value = "servicos", key = "@chaveDeCache.de(#servicoId)")
 * }</pre>
 *
 * <p><strong>Cache que precisa de invalidação explícita declara a chave assim, sempre.</strong>
 * O {@link GeradorDeChaveComTenant} padrão produz {@code tenant|metodo(args)} — isolado, mas com
 * um formato que {@link InvalidadorDeCache} não teria como reconstruir a partir da chave de
 * negócio. O gerador padrão é a rede de segurança contra vazamento entre estabelecimentos; este
 * bean é o que torna a entrada endereçável de fora.
 */
public class ChavesDeCache {

    public String de(Object chaveDeNegocio) {
        return ChaveDeCache.de(chaveDeNegocio);
    }
}
