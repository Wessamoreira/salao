package br.com.salao.conversacional.internal.infra;

import br.com.salao.shared.manutencao.ConexaoDeManutencao;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * RT-CNV-007 — a retenção da trilha do agente (docs/04).
 *
 * <ul>
 *   <li><strong>Aos 90 dias, o conteúdo sai.</strong> Pedido, resposta, telefone, argumentos das
 *       ferramentas e resumos das intenções — tudo o que carrega mensagem ou nome de cliente. Fica o
 *       esqueleto: quando, em nome de quem, quais ferramentas e com que desfecho, se gravou, se foi
 *       bloqueada, quanto custou. É o que ainda responde "o agente fez isso?" sem guardar a conversa;
 *   <li><strong>Aos 12 meses, a linha sai.</strong> Passado isso não responde mais nada que alguém
 *       pergunte, e trilha que cresce para sempre vira, ela própria, repositório de dado pessoal.
 * </ul>
 *
 * <p>Pela conexão de manutenção, que atravessa salões e só tem {@code update} nas colunas de conteúdo:
 * o expurgo pode apagar o que foi dito, e não reescrever o que aconteceu.
 */
public class PurgadorDeAcoesDoAgente {

    private static final Logger log = LoggerFactory.getLogger(PurgadorDeAcoesDoAgente.class);

    private static final String ANONIMIZAR = """
            update acao_ia
               set pedido = null, resposta = null, de_telefone = null,
                   ferramentas = coalesce((select jsonb_agg(jsonb_build_object(
                                                'nome', f->>'nome', 'desfecho', f->>'desfecho'))
                                             from jsonb_array_elements(ferramentas) f), '[]'::jsonb),
                   intencoes = coalesce((select jsonb_agg(i - 'resumo')
                                           from jsonb_array_elements(intencoes) i), '[]'::jsonb),
                   anonimizada_em = now()
             where anonimizada_em is null
               and ocorrida_em < now() - cast(:prazo as interval)
            """;

    private static final String APAGAR = """
            delete from acao_ia where ocorrida_em < now() - cast(:prazo as interval)
            """;

    private final ConexaoDeManutencao manutencao;
    private final Duration anonimizarApos;
    private final Duration apagarApos;

    public PurgadorDeAcoesDoAgente(ConexaoDeManutencao manutencao, Duration anonimizarApos,
                                   Duration apagarApos) {
        if (!apagarApos.minus(anonimizarApos).isPositive()) {
            throw new IllegalArgumentException("apagar precisa vir depois de anonimizar");
        }
        this.manutencao = manutencao;
        this.anonimizarApos = anonimizarApos;
        this.apagarApos = apagarApos;
    }

    public record Resultado(int apagadas, int anonimizadas) {
    }

    /** Diário, às 3h45 — depois do expurgo da auditoria, fora do expediente. */
    @Scheduled(cron = "0 45 3 * * *")
    public void purgar() {
        var resultado = executar();
        if (resultado.apagadas() > 0 || resultado.anonimizadas() > 0) {
            log.info("Trilha do agente: {} apagadas, {} anonimizadas", resultado.apagadas(),
                    resultado.anonimizadas());
        }
    }

    /** Exposto para teste e para acionamento manual pelo runbook. Apaga antes de anonimizar. */
    public Resultado executar() {
        int apagadas = manutencao.jdbc().sql(APAGAR)
                .param("prazo", apagarApos.toSeconds() + " seconds")
                .update();
        int anonimizadas = manutencao.jdbc().sql(ANONIMIZAR)
                .param("prazo", anonimizarApos.toSeconds() + " seconds")
                .update();
        return new Resultado(apagadas, anonimizadas);
    }
}
