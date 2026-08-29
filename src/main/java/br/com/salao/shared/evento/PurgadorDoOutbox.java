package br.com.salao.shared.evento;

import br.com.salao.shared.manutencao.ConexaoDeManutencao;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * RT-INF-006 — expurga o arquivo de publicações concluídas.
 *
 * <p>Com {@code completion-mode=archive}, a publicação concluída sai da tabela quente e vai para
 * {@code event_publication_archive}. Isso mantém o rastro de "esta notificação saiu?", que é a
 * pergunta que o suporte realmente faz — mas move o crescimento, não o elimina. Sem expurgo, é o
 * risco R-12 com outro nome: nada quebra, o disco só enche.
 */
public class PurgadorDoOutbox {

    private static final Logger log = LoggerFactory.getLogger(PurgadorDoOutbox.class);

    private static final String PURGAR = """
            delete from event_publication_archive
             where completion_date is not null
               and completion_date < now() - cast(? as interval)
            """;

    private final ConexaoDeManutencao manutencao;
    private final Duration retencao;

    public PurgadorDoOutbox(ConexaoDeManutencao manutencao, Duration retencao) {
        this.manutencao = manutencao;
        this.retencao = retencao;
    }

    /** Diário, às 3h10 — logo depois da purga de idempotência, fora do expediente do salão. */
    @Scheduled(cron = "0 10 3 * * *")
    public void purgar() {
        int removidos = executar(retencao);
        if (removidos > 0) {
            log.info("Outbox: {} publicações arquivadas removidas", removidos);
        }
    }

    /** Exposto para teste e para acionamento manual pelo runbook. */
    public int executar(Duration retencao) {
        return manutencao.jdbc().sql(PURGAR)
                .param(1, retencao.toSeconds() + " seconds")
                .update();
    }
}
