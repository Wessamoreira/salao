package br.com.salao.conversacional.internal.infra;

import br.com.salao.conversacional.internal.application.EnviadorDoWhatsApp;
import br.com.salao.shared.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * RT-CNV-006 — a saída para a equipe, dentro do escopo do salão.
 *
 * <p>Só enfileira. Gravar antes de enviar é o padrão de RT-NOT-001: a falha de rede vira linha para
 * tentar de novo, e o envio acontece fora de transação ({@code DespachanteParaEquipe}). Enfileirar
 * acontece DENTRO da transação de quem produziu a mensagem — a conversa encerrada, a intenção decidida
 * — para que as duas coisas existam juntas ou nenhuma.
 *
 * <p>Uma mensagem por motivo, origem e destino ({@code on conflict do nothing}): a execução que volta à
 * fila depois de uma queda não responde duas vezes.
 */
public class MensagensParaEquipeJdbc {

    public enum Motivo {
        /** A resposta à mensagem que a pessoa mandou. */
        RESPOSTA,
        /** O "SIM + código" de uma intenção proposta na conversa (RT-CNV-005). */
        PEDIDO_DE_CONFIRMACAO,
        /** O pedido, com botões, a quem precisa aceitar. */
        PEDIDO_DE_ACEITE,
        /** O que aconteceu com o aceite, para quem pediu. */
        DESFECHO_DO_ACEITE,
        /**
         * RT-CNV-008 — o aviso de IA a quem não é da equipe, uma vez por número. O único motivo cujo
         * destino não é da equipe: a fila é a saída do número do salão, e o nome ficou do começo.
         */
        AVISO_DE_IA
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public MensagensParaEquipeJdbc(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** @return verdadeiro se entrou agora; falso se a mesma mensagem já estava na fila */
    @Transactional
    public boolean enfileirar(Motivo motivo, UUID origemId, String destino, String texto,
                              List<EnviadorDoWhatsApp.Botao> botoes, Instant agora) {
        return jdbc.sql("""
                insert into mensagem_para_equipe
                    (estabelecimento_id, destino, motivo, origem_id, texto, botoes,
                     proxima_tentativa_em, criada_em)
                values (:estabelecimento, :destino, :motivo, :origem, :texto,
                        cast(:botoes as jsonb), :agora, :agora)
                on conflict (motivo, origem_id, destino) do nothing
                """)
                .param("estabelecimento", TenantContext.obrigatorio())
                .param("destino", destino)
                .param("motivo", motivo.name())
                .param("origem", origemId)
                .param("texto", texto)
                .param("botoes", botoes.isEmpty() ? null : json.writeValueAsString(botoes))
                .param("agora", Timestamp.from(agora))
                .update() == 1;
    }
}
