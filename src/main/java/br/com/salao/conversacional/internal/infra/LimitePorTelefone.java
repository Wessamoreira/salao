package br.com.salao.conversacional.internal.infra;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.UUID;

/**
 * RT-CNV-004 — conversas por telefone por hora (docs/05: "rate limit por telefone").
 *
 * <p>Protege o teto do salão de um único número em laço — um bot do outro lado, uma pessoa colando o
 * mesmo texto — e de quem descobriu o número da recepção.
 *
 * <p>Por instância, como o limite por IP de RT-INF-011, e com a mesma consequência assumida: com N
 * instâncias o limite efetivo é N vezes maior. O TETO de custo, que é o que protege o dinheiro, é
 * global — está no banco.
 */
public class LimitePorTelefone {

    private final Cache<String, Bucket> baldes = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofHours(2))
            .build();
    private final int porHora;

    public LimitePorTelefone(int porHora) {
        if (porHora < 1) {
            throw new IllegalStateException("app.agente.limite-por-telefone-por-hora precisa ser >= 1");
        }
        this.porHora = porHora;
    }

    public boolean consumir(UUID estabelecimentoId, String telefone) {
        return baldes.get(estabelecimentoId + "|" + telefone, chave -> Bucket.builder()
                        .addLimit(Bandwidth.builder().capacity(porHora)
                                .refillGreedy(porHora, Duration.ofHours(1)).build())
                        .build())
                .tryConsume(1);
    }
}
