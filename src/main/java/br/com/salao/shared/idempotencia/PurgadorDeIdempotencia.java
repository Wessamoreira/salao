package br.com.salao.shared.idempotencia;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import br.com.salao.shared.manutencao.ConexaoDeManutencao;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * RT-INF-005 — expurgo dos registros vencidos.
 *
 * <p>Sem isto a tabela cresce para sempre. É o mesmo modo de falha do {@code event_publication} do
 * Modulith (risco R-12): nada quebra, o disco só enche — e enche primeiro em produção, onde há
 * volume, não em desenvolvimento.
 *
 * <p>Roda pela conexão de manutenção, nunca pela da aplicação. Chega a mais de um estabelecimento
 * por definição, e é justamente esse poder que {@code salao_app} não pode ter.
 *
 * <p>Sem {@code @Transactional} de propósito: em autocommit, o gerenciador de transação que exige
 * tenant não participa — e ele exigiria um tenant que esta operação legitimamente não tem.
 */
public class PurgadorDeIdempotencia {

    private static final Logger log = LoggerFactory.getLogger(PurgadorDeIdempotencia.class);

    private static final String PURGAR = "delete from idempotencia where expira_em < now()";

    private final ConexaoDeManutencao manutencao;

    public PurgadorDeIdempotencia(ConexaoDeManutencao manutencao) {
        this.manutencao = manutencao;
    }

    /** Diário, às 3h. Fora do horário de funcionamento de qualquer salão. */
    @Scheduled(cron = "0 0 3 * * *")
    public void purgar() {
        int removidos = executar();
        if (removidos > 0) {
            log.info("Idempotência: {} registros vencidos removidos", removidos);
        }
    }

    /** Exposto para teste e para acionamento manual pelo runbook. */
    public int executar() {
        return manutencao.jdbc().sql(PURGAR).update();
    }
}
