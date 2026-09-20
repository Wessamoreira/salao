package br.com.salao.financeiro.internal.infra;

import br.com.salao.financeiro.internal.domain.FaixaDeTaxa;
import br.com.salao.financeiro.internal.domain.TaxaContratada;
import br.com.salao.shared.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** RT-FIN-012 — o que o salão negociou com a adquirente, por faixa. */
public class TaxasDoAdquirenteJdbc {

    /** Só a Stone por enquanto; o modelo já é por adquirente (ADR-0007). */
    public static final String ADQUIRENTE = "STONE";

    private final JdbcClient jdbc;

    public TaxasDoAdquirenteJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** As faixas cadastradas. Faixa ausente do mapa é faixa sem contrato — e sem previsão. */
    @Transactional(readOnly = true)
    public Map<FaixaDeTaxa, TaxaContratada> contratadas() {
        Map<FaixaDeTaxa, TaxaContratada> porFaixa = new EnumMap<>(FaixaDeTaxa.class);
        jdbc.sql("""
                select faixa, percentual, dias_para_receber
                  from taxa_do_adquirente
                 where adquirente = :adquirente
                """)
                .param("adquirente", ADQUIRENTE)
                .query((rs, n) -> new TaxaContratada(FaixaDeTaxa.valueOf(rs.getString("faixa")),
                        rs.getBigDecimal("percentual"), rs.getInt("dias_para_receber")))
                .list()
                .forEach(taxa -> porFaixa.put(taxa.faixa(), taxa));
        return porFaixa;
    }

    /**
     * Grava as faixas informadas, em cima do que havia.
     *
     * <p>{@code on conflict do update} e não apagar-e-inserir: a linha é a mesma coisa ao longo do
     * tempo, e apagar deixaria uma janela — dentro da transação, mas ainda assim — em que o salão não
     * tem taxa nenhuma.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void gravar(List<TaxaContratada> faixas, Instant agora, UUID por) {
        UUID tenant = TenantContext.obrigatorio();
        for (TaxaContratada taxa : faixas) {
            jdbc.sql("""
                    insert into taxa_do_adquirente (estabelecimento_id, adquirente, faixa,
                                                    percentual, dias_para_receber, atualizada_em,
                                                    atualizada_por)
                    values (:estabelecimento, :adquirente, :faixa, :percentual, :dias, :agora, :por)
                    on conflict (estabelecimento_id, adquirente, faixa) do update
                       set percentual = excluded.percentual,
                           dias_para_receber = excluded.dias_para_receber,
                           atualizada_em = excluded.atualizada_em,
                           atualizada_por = excluded.atualizada_por
                    """)
                    .param("estabelecimento", tenant)
                    .param("adquirente", ADQUIRENTE)
                    .param("faixa", taxa.faixa().name())
                    .param("percentual", taxa.percentual())
                    .param("dias", taxa.diasParaReceber())
                    .param("agora", Timestamp.from(agora))
                    .param("por", por)
                    .update();
        }
    }
}
