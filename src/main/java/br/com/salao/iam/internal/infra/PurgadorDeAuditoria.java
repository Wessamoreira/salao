package br.com.salao.iam.internal.infra;

import br.com.salao.shared.manutencao.ConexaoDeManutencao;
import java.time.Duration;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * RT-IAM-008 — retenção da trilha.
 *
 * <p>Dois prazos, e a diferença é o motivo pelo qual cada registro existe:
 *
 * <ul>
 *   <li><strong>Agenda e financeiro: 5 anos.</strong> São os registros que respondem a disputa
 *       — com cliente sobre um horário, com profissional sobre um repasse. O prazo acompanha o
 *       tempo em que essas discussões ainda podem aparecer;
 *   <li><strong>O resto: 1 ano.</strong> Passado isso não responde mais nada que alguém pergunte,
 *       e guardar dado pessoal sem finalidade contraria a minimização da LGPD.
 * </ul>
 *
 * <p>Apagar por prazo <em>é</em> parte da política, não descuido: trilha que cresce para sempre
 * vira, ela própria, um repositório de dado pessoal que ninguém revisa.
 */
public class PurgadorDeAuditoria {

    private static final Logger log = LoggerFactory.getLogger(PurgadorDeAuditoria.class);

    /** Entidades cujo registro responde a disputa e por isso vive mais. */
    private static final Set<String> CRITICAS = Set.of(
            "agendamento", "comanda", "pagamento", "lancamento", "fechamento", "comissao");

    private static final String PURGAR = """
            delete from auditoria
             where ocorrido_em < now() - cast(:retencao as interval)
               and (entidade = any(:criticas)) = :critica
            """;

    private final ConexaoDeManutencao manutencao;
    private final Duration retencaoCritica;
    private final Duration retencaoPadrao;

    public PurgadorDeAuditoria(ConexaoDeManutencao manutencao, Duration retencaoCritica,
                               Duration retencaoPadrao) {
        this.manutencao = manutencao;
        this.retencaoCritica = retencaoCritica;
        this.retencaoPadrao = retencaoPadrao;
    }

    /** Diário, às 3h30 — depois dos demais expurgos, fora do expediente. */
    @Scheduled(cron = "0 30 3 * * *")
    public void purgar() {
        int removidos = executar(retencaoCritica, retencaoPadrao);
        if (removidos > 0) {
            log.info("Auditoria: {} registros removidos por retenção", removidos);
        }
    }

    /** Exposto para teste e para acionamento manual pelo runbook. */
    public int executar(Duration critica, Duration padrao) {
        return apagar(critica, true) + apagar(padrao, false);
    }

    private int apagar(Duration retencao, boolean critica) {
        return manutencao.jdbc().sql(PURGAR)
                .param("retencao", retencao.toSeconds() + " seconds")
                .param("criticas", CRITICAS.toArray(String[]::new))
                .param("critica", critica)
                .update();
    }
}
