package br.com.salao.conversacional.internal.infra;

import br.com.salao.shared.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CNV-008 — quem já foi avisado, pela impressão do número (RN-CNV-029).
 *
 * <p>{@code MANDATORY}: registrar e enfileirar o aviso são o mesmo fato. Separados, uma queda entre os
 * dois deixaria o número marcado como avisado sem aviso nenhum na fila — e ninguém o avisaria depois.
 */
public class AvisosDeIaJdbc {

    private final JdbcClient jdbc;

    public AvisosDeIaJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return verdadeiro se este número foi registrado agora; falso se já tinha sido avisado — inclusive
     *         por uma mensagem simultânea, que disputou a mesma linha e venceu
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean registrar(byte[] impressao, UUID mensagemId, Instant agora) {
        return jdbc.sql("""
                insert into aviso_de_ia (estabelecimento_id, impressao, mensagem_id, avisado_em)
                values (:estabelecimento, :impressao, :mensagem, :agora)
                on conflict (estabelecimento_id, impressao) do nothing
                """)
                .param("estabelecimento", TenantContext.obrigatorio())
                .param("impressao", impressao)
                .param("mensagem", mensagemId)
                .param("agora", Timestamp.from(agora))
                .update() == 1;
    }
}
