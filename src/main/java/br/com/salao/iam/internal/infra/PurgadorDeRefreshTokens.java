package br.com.salao.iam.internal.infra;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * RT-IAM-004 — expurgo de refresh vencido.
 *
 * <p>A tabela cresce a cada login e a cada renovação: um usuário ativo gera dezenas de linhas por
 * mês, e nada as remove. É o mesmo modo de falha silenciosa do outbox e da idempotência (risco
 * R-12) — nada quebra, o disco só enche.
 *
 * <p>Mantém os vencidos por um período <em>além</em> do vencimento, de propósito: é o que permite
 * responder "quando esta sessão foi encerrada, e a partir de qual IP?" numa investigação. Passado
 * esse prazo não serve nem para isso.
 */
public class PurgadorDeRefreshTokens {

    private static final Logger log = LoggerFactory.getLogger(PurgadorDeRefreshTokens.class);

    private final RefreshTokensJdbc tokens;
    private final Duration retencaoAlemDoVencimento;

    public PurgadorDeRefreshTokens(RefreshTokensJdbc tokens, Duration retencaoAlemDoVencimento) {
        this.tokens = tokens;
        this.retencaoAlemDoVencimento = retencaoAlemDoVencimento;
    }

    /** Diário, às 3h20 — depois da idempotência e do outbox, fora do expediente do salão. */
    @Scheduled(cron = "0 20 3 * * *")
    public void purgar() {
        int removidos = executar(retencaoAlemDoVencimento);
        if (removidos > 0) {
            log.info("Refresh tokens vencidos removidos: {}", removidos);
        }
    }

    /** Exposto para teste e para acionamento manual pelo runbook. */
    public int executar(Duration retencao) {
        return tokens.purgarVencidos(retencao);
    }
}
