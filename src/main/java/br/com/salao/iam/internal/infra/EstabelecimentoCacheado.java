package br.com.salao.iam.internal.infra;

import br.com.salao.iam.api.ConfiguracaoDoEstabelecimento;
import br.com.salao.iam.api.EstabelecimentoApi;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.cache.annotation.Cacheable;

/**
 * RT-IAM-001 — decorador de cache sobre {@link EstabelecimentoJdbc}.
 *
 * <h2>Por que um decorador, e não {@code @Cacheable} no próprio Jdbc</h2>
 *
 * <p>Empilhar {@code @Cacheable} e {@code @Transactional} no mesmo método deixa a ordem entre as
 * duas advices indefinida — as duas usam {@code LOWEST_PRECEDENCE}. Se a transação abrir primeiro,
 * até um acerto de cache paga uma transação e um {@code set_config}, que é exatamente o custo que
 * o cache existe para evitar. Separar em dois beans torna a ordem um fato do código, e não uma
 * suposição sobre o Spring.
 *
 * <p>É a mesma lição do {@code PropagadorDeContexto} (RT-INF-006), onde a ordem entre
 * {@code @Async} e {@code @Transactional} também não era confiável.
 *
 * <p>A chave usa {@code @chaveDeCache.de(...)}, então é endereçável pelo
 * {@code InvalidadorDeCache} quando a configuração mudar — o que acontece em outra rotina, e por
 * isso está listado como pendência.
 */
public class EstabelecimentoCacheado implements EstabelecimentoApi {

    public static final String CACHE = "configuracao";

    private final EstabelecimentoApi delegado;

    public EstabelecimentoCacheado(EstabelecimentoApi delegado) {
        this.delegado = delegado;
    }

    @Override
    @Cacheable(value = CACHE, key = "@chaveDeCache.de(#estabelecimentoId)")
    public Optional<ConfiguracaoDoEstabelecimento> configuracao(UUID estabelecimentoId) {
        return delegado.configuracao(estabelecimentoId);
    }

    @Override
    public ZoneId fusoDe(UUID estabelecimentoId) {
        // Passa pelo método cacheado deste mesmo bean via a referência externa? Não: chamada
        // interna não atravessa o proxy. Delega ao objeto decorado e deixa o cache para quem
        // chamar configuracao() — fusoDe é atalho de conveniência, não caminho quente.
        return delegado.fusoDe(estabelecimentoId);
    }
}
