package br.com.salao.conversacional.internal.infra;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CNV-004 / RN-CNV-011 — o teto de custo por salão por dia. Ver V53.
 *
 * <p>A reserva é UM comando: {@code insert ... on conflict do update ... where cabe}. Com a linha do
 * dia travada pelo próprio conflito, duas reservas concorrentes nunca passam juntas pelo mesmo saldo.
 */
public class ConsumoDeIaJdbc {

    private final JdbcClient jdbc;

    public ConsumoDeIaJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return verdadeiro se o pior caso coube no que resta do dia, e foi reservado */
    @Transactional
    public boolean reservar(UUID estabelecimentoId, LocalDate dia, BigDecimal piorCaso,
                            BigDecimal teto) {
        if (piorCaso.compareTo(teto) > 0) {
            return false;
        }
        return jdbc.sql("""
                insert into consumo_de_ia (estabelecimento_id, dia, custo_reservado_usd)
                values (:estabelecimento, :dia, :valor)
                on conflict (estabelecimento_id, dia) do update
                   set custo_reservado_usd = consumo_de_ia.custo_reservado_usd
                                             + excluded.custo_reservado_usd
                 where consumo_de_ia.custo_confirmado_usd + consumo_de_ia.custo_reservado_usd
                       + excluded.custo_reservado_usd <= :teto
                returning 1
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("dia", dia)
                .param("valor", piorCaso)
                .param("teto", teto)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    @Transactional
    public void liquidar(UUID estabelecimentoId, LocalDate dia, BigDecimal reservado,
                         BigDecimal real, int tokensEntrada, int tokensSaida) {
        jdbc.sql("""
                update consumo_de_ia
                   set custo_reservado_usd = greatest(custo_reservado_usd - :reservado, 0),
                       custo_confirmado_usd = custo_confirmado_usd + :real,
                       tokens_entrada = tokens_entrada + :entrada,
                       tokens_saida = tokens_saida + :saida,
                       chamadas = chamadas + case when :entrada + :saida > 0 then 1 else 0 end
                 where estabelecimento_id = :estabelecimento and dia = :dia
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("dia", dia)
                .param("reservado", reservado)
                .param("real", real)
                .param("entrada", tokensEntrada)
                .param("saida", tokensSaida)
                .update();
    }
}
