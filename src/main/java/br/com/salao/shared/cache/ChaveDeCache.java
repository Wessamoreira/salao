package br.com.salao.shared.cache;

import br.com.salao.shared.tenant.TenantContext;
import java.util.UUID;

/**
 * RT-INF-007 — toda chave de cache começa pelo tenant.
 *
 * <p><strong>É a peça que impede o cache de desfazer a RLS.</strong> Uma entrada guardada apenas
 * por {@code servicoId} seria servida a qualquer estabelecimento que pedisse o mesmo id — e o
 * banco nem chegaria a ser consultado, então nem a RLS nem a checagem de posse teriam chance de
 * agir. O isolamento cairia no ponto exato em que ninguém está olhando.
 *
 * <p>O prefixo também é o que permite invalidar tudo de um estabelecimento sem tocar nos outros.
 */
public final class ChaveDeCache {

    private static final char SEPARADOR = '|';

    private ChaveDeCache() {
    }

    /** Chave completa a partir do tenant no escopo. Falha se não houver — nunca assume um. */
    public static String de(Object chaveDeNegocio) {
        return de(TenantContext.obrigatorio(), chaveDeNegocio);
    }

    public static String de(UUID estabelecimentoId, Object chaveDeNegocio) {
        return estabelecimentoId + String.valueOf(SEPARADOR) + chaveDeNegocio;
    }

    /** Prefixo de todas as chaves de um estabelecimento. */
    public static String prefixoDe(UUID estabelecimentoId) {
        return estabelecimentoId + String.valueOf(SEPARADOR);
    }

    public static boolean pertenceA(Object chave, UUID estabelecimentoId) {
        return chave instanceof String texto && texto.startsWith(prefixoDe(estabelecimentoId));
    }
}
