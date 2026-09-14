package br.com.salao.conversacional.internal.infra;

import br.com.salao.shared.manutencao.ConexaoDeManutencao;
import br.com.salao.shared.tempo.Relogio;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * RT-CNV-006 — o aceite que venceu sem resposta, e o aviso a quem pediu.
 *
 * <p>A validade do aceite não pode ser só lida na hora do toque, como a do código de confirmação: se a
 * profissional nunca tocar, a recepção nunca saberia. Então alguém precisa passar de tempos em tempos.
 *
 * <h2>Um comando só</h2>
 *
 * <p>Marcar {@code EXPIRADA} e enfileirar o aviso acontecem no MESMO comando — um {@code UPDATE} dentro
 * de um {@code WITH} que alimenta o {@code INSERT}. Em dois comandos, uma queda entre eles deixaria a
 * intenção vencida e ninguém avisado, e nenhuma rodada seguinte a encontraria de novo.
 *
 * <p>O texto é montado no SQL por isso mesmo. Duas instâncias ao mesmo tempo não avisam duas vezes: o
 * {@code UPDATE} trava a linha, e a segunda não a encontra mais {@code AGUARDANDO_ACEITE}; e o índice
 * único da saída absorve o aviso que o toque atrasado tiver produzido primeiro.
 */
public class ExpiradorDeAceites {

    private static final Logger log = LoggerFactory.getLogger(ExpiradorDeAceites.class);

    private static final String EXPIRAR = """
            with vencidas as (
                update intencao_pendente
                   set status = 'EXPIRADA', aceite_decidido_em = :agora
                 where status = 'AGUARDANDO_ACEITE' and aceite_expira_em <= :agora
                returning id, estabelecimento_id, de_telefone, aceite_de_nome, resumo)
            insert into mensagem_para_equipe
                (estabelecimento_id, destino, motivo, origem_id, texto, proxima_tentativa_em,
                 criada_em)
            select estabelecimento_id, de_telefone, 'DESFECHO_DO_ACEITE', id,
                   aceite_de_nome || ' não respondeu a tempo: ' || resumo || '. Nada foi gravado.',
                   :agora, :agora
              from vencidas
            on conflict (motivo, origem_id, destino) do nothing
            """;

    private final ConexaoDeManutencao manutencao;
    private final Relogio relogio;
    private final MeterRegistry metricas;

    public ExpiradorDeAceites(ConexaoDeManutencao manutencao, Relogio relogio,
                              MeterRegistry metricas) {
        this.manutencao = manutencao;
        this.relogio = relogio;
        this.metricas = metricas;
    }

    @Scheduled(fixedDelayString = "${app.agente.aceite.intervalo:PT1M}",
               initialDelayString = "${app.agente.aceite.atraso-inicial:PT40S}")
    public void expirar() {
        int avisos = executar();
        if (avisos > 0) {
            log.info("Aceites vencidos: {} aviso(s) a quem pediu", avisos);
        }
    }

    /** Exposto para teste e para acionamento manual pelo runbook. @return avisos enfileirados */
    public int executar() {
        int avisos = manutencao.jdbc().sql(EXPIRAR)
                .param("agora", OffsetDateTime.ofInstant(relogio.agora(), ZoneOffset.UTC))
                .update();
        if (avisos > 0) {
            metricas.counter("conversacional.agente", "desfecho", "aceite_vencido").increment(avisos);
        }
        return avisos;
    }
}
