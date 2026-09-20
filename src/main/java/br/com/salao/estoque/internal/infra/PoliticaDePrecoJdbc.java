package br.com.salao.estoque.internal.infra;

import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** RT-EST-010 — a margem mínima do salão, que nenhuma sugestão fura. */
public class PoliticaDePrecoJdbc {

    private final JdbcClient jdbc;

    public PoliticaDePrecoJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Vazio enquanto o salão não definiu a régua — e sem régua não há sugestão. */
    @Transactional(readOnly = true)
    public Optional<BigDecimal> margemMinima() {
        return jdbc.sql("select margem_minima from politica_de_preco")
                .query(BigDecimal.class)
                .optional();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void definir(BigDecimal margemMinima, Instant agora, UUID por) {
        jdbc.sql("""
                insert into politica_de_preco (estabelecimento_id, margem_minima, atualizada_em,
                                               atualizada_por)
                values (:estabelecimento, :margem, :agora, :por)
                on conflict (estabelecimento_id) do update
                   set margem_minima = excluded.margem_minima,
                       atualizada_em = excluded.atualizada_em,
                       atualizada_por = excluded.atualizada_por
                """)
                .param("estabelecimento", TenantContext.obrigatorio())
                .param("margem", margemMinima)
                .param("agora", Timestamp.from(agora))
                .param("por", por)
                .update();
    }
}
