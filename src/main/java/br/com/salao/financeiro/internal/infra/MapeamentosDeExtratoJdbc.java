package br.com.salao.financeiro.internal.infra;

import br.com.salao.financeiro.internal.domain.CampoDoExtrato;
import br.com.salao.financeiro.internal.domain.FormatoDeNumero;
import br.com.salao.financeiro.internal.domain.MapeamentoDoExtrato;
import br.com.salao.shared.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** RT-FIN-009 — o mapeamento que o salão confirmou na última importação, pelo nome da coluna. */
public class MapeamentosDeExtratoJdbc {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public MapeamentosDeExtratoJdbc(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Optional<MapeamentoDoExtrato> doSalao(String adquirente) {
        return jdbc.sql("""
                select colunas::text as colunas, formato_de_numero from mapeamento_de_extrato
                 where adquirente = :adquirente
                """)
                .param("adquirente", adquirente)
                .query((rs, n) -> new MapeamentoDoExtrato(colunas(rs.getString("colunas")),
                        FormatoDeNumero.valueOf(rs.getString("formato_de_numero"))))
                .optional();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void salvar(String adquirente, MapeamentoDoExtrato mapeamento, Instant agora) {
        jdbc.sql("""
                insert into mapeamento_de_extrato
                    (estabelecimento_id, adquirente, colunas, formato_de_numero, atualizado_em)
                values (:estabelecimento, :adquirente, cast(:colunas as jsonb), :formato, :agora)
                on conflict (estabelecimento_id, adquirente) do update
                   set colunas = excluded.colunas, formato_de_numero = excluded.formato_de_numero,
                       atualizado_em = excluded.atualizado_em
                """)
                .param("estabelecimento", TenantContext.obrigatorio())
                .param("adquirente", adquirente)
                .param("colunas", json.writeValueAsString(mapeamento.colunas()))
                .param("formato", mapeamento.formato().name())
                .param("agora", Timestamp.from(agora))
                .update();
    }

    /** Campo que deixou de existir numa versão futura é ignorado, e não derruba a leitura. */
    private Map<CampoDoExtrato, String> colunas(String texto) {
        @SuppressWarnings("unchecked")
        Map<String, Object> lido = json.readValue(texto, Map.class);
        var colunas = new EnumMap<CampoDoExtrato, String>(CampoDoExtrato.class);
        lido.forEach((campo, coluna) -> {
            for (CampoDoExtrato conhecido : CampoDoExtrato.values()) {
                if (conhecido.name().equals(campo) && coluna != null) {
                    colunas.put(conhecido, coluna.toString());
                }
            }
        });
        return colunas;
    }
}
