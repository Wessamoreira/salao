package br.com.salao.cliente.internal.domain;

import java.text.Normalizer;
import java.util.Locale;

/**
 * RT-CLI-003 — nome em minúsculas e sem acento, para comparação.
 *
 * <p>Existe apesar de o banco já normalizar a coluna {@code busca} (RN-CLI-011), e a distinção
 * importa: aquela normalização serve a <strong>índice</strong> e precisa morar no banco, junto do
 * índice que depende dela. Esta serve a uma <strong>decisão de domínio</strong> — comparar
 * primeiros nomes — que precisa ser testável sem subir Postgres.
 *
 * <p>Não são a mesma regra em dois lugares: são duas regras com finalidades diferentes que por
 * acaso fazem a mesma coisa hoje. Se um dia divergirem, nada quebra em silêncio — a busca continua
 * indexada e a comparação continua comparável.
 */
public final class SemAcento {

    private SemAcento() {
    }

    public static String de(String texto) {
        if (texto == null) {
            return "";
        }
        return Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }
}
