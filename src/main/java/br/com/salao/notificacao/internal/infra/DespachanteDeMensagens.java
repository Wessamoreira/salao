package br.com.salao.notificacao.internal.infra;

import br.com.salao.notificacao.api.CanalDeEnvio;
import br.com.salao.notificacao.api.Mensageiro;
import br.com.salao.notificacao.internal.application.CatalogoDeModelos;
import br.com.salao.notificacao.internal.domain.EsperaEntreTentativas;
import br.com.salao.shared.manutencao.ConexaoDeManutencao;
import br.com.salao.shared.tempo.Relogio;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.ObjectMapper;

/**
 * RT-NOT-001 — quem tira da fila e manda.
 *
 * <h2>Pela conexão de manutenção, como o purgador</h2>
 *
 * <p>O despachante atende <strong>todos</strong> os salões: ele roda por fora de qualquer
 * requisição e não tem tenant. É exatamente o poder que {@code salao_app} não pode ter, e por isso
 * a conexão é a de manutenção — a mesma decisão de {@code PurgadorDeIdempotencia}.
 *
 * <p>Sem {@code @Transactional} pelo mesmo motivo que lá: em autocommit, o gerenciador que exige
 * tenant não participa.
 *
 * <h2>{@code for update skip locked}</h2>
 *
 * <p>Mais de uma instância roda isto ao mesmo tempo. Sem o lock, as duas pegariam a mesma linha e a
 * cliente receberia o lembrete duas vezes — o mesmo problema do agendamento duplicado, resolvido
 * pelo mesmo lugar: o banco.
 *
 * <h2>Falhar é normal</h2>
 *
 * <p>Provedor fora do ar, limite de taxa, número inválido. Tudo vira "tenta de novo mais tarde",
 * com espera dobrando, até a quinta — e aí a mensagem é dada como falha, que é um estado que alguém
 * pode olhar, e não um envio que ninguém sabe se aconteceu.
 */
public class DespachanteDeMensagens {

    private static final Logger log = LoggerFactory.getLogger(DespachanteDeMensagens.class);

    /** Quantas por rodada. Baixo de propósito: a rodada é a cada minuto e o salão não tem pressa. */
    private static final int LOTE = 50;

    private static final String PROXIMAS = """
            select id, cliente_id, canal, modelo, destino, parametros, tentativas
              from mensagem
             where status = 'PENDENTE' and proxima_tentativa_em <= ?
             order by proxima_tentativa_em
             limit ?
               for update skip locked
            """;

    private final ConexaoDeManutencao manutencao;
    private final Mensageiro mensageiro;
    private final CatalogoDeModelos modelos;
    private final ObjectMapper json;
    private final Relogio relogio;

    public DespachanteDeMensagens(ConexaoDeManutencao manutencao, Mensageiro mensageiro,
                                  CatalogoDeModelos modelos, ObjectMapper json, Relogio relogio) {
        this.manutencao = manutencao;
        this.mensageiro = mensageiro;
        this.modelos = modelos;
        this.json = json;
        this.relogio = relogio;
    }

    private record NaFila(UUID id, CanalDeEnvio canal, String modelo, String destino,
                          Map<String, String> parametros, int tentativas) {
    }

    /** A cada minuto. Lembrete de salão não precisa de segundos. */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT30S")
    public void despachar() {
        int enviadas = executar();
        if (enviadas > 0) {
            log.info("Notificação: {} mensagem(ns) despachada(s)", enviadas);
        }
    }

    /** Exposto para teste e para acionamento manual pelo runbook. */
    public int executar() {
        Instant agora = relogio.agora();
        List<NaFila> lote = manutencao.jdbc().sql(PROXIMAS)
                .param(OffsetDateTime.ofInstant(agora, ZoneOffset.UTC))
                .param(LOTE)
                .query((rs, n) -> new NaFila(
                        rs.getObject("id", UUID.class),
                        CanalDeEnvio.valueOf(rs.getString("canal")),
                        rs.getString("modelo"),
                        rs.getString("destino"),
                        lerParametros(rs.getString("parametros")),
                        rs.getInt("tentativas")))
                .list();

        int enviadas = 0;
        for (NaFila mensagem : lote) {
            if (tentar(mensagem, agora)) {
                enviadas++;
            }
        }
        return enviadas;
    }

    private boolean tentar(NaFila mensagem, Instant agora) {
        try {
            // O texto é montado AGORA, e não no enfileiramento: assim uma correção de redação
            // alcança o que ainda está na fila.
            String texto = modelos.porNome(mensagem.modelo())
                    .orElseThrow(() -> new Mensageiro.FalhaDeEnvio(
                            "modelo " + mensagem.modelo() + " não existe mais"))
                    .preencher(mensagem.parametros());

            mensageiro.enviar(mensagem.canal(), mensagem.destino(), texto);
            marcarEnviada(mensagem.id());
            return true;
        } catch (RuntimeException e) {
            registrarFalha(mensagem, agora, e);
            return false;
        }
    }

    private void registrarFalha(NaFila mensagem, Instant agora, RuntimeException erro) {
        int tentativas = mensagem.tentativas() + 1;
        boolean desistir = EsperaEntreTentativas.desistir(tentativas);
        Instant proxima = agora.plus(EsperaEntreTentativas.apos(tentativas));

        manutencao.jdbc().sql("""
                update mensagem
                   set status = ?, tentativas = ?, proxima_tentativa_em = ?, ultimo_erro = ?
                 where id = ?
                """)
                .param(desistir ? "FALHOU" : "PENDENTE")
                .param(tentativas)
                .param(OffsetDateTime.ofInstant(proxima, ZoneOffset.UTC))
                // Truncado: mensagem de provedor às vezes traz o corpo inteiro da resposta, e a
                // coluna viraria um log dentro do banco.
                .param(recortar(erro.getMessage()))
                .param(mensagem.id())
                .update();

        if (desistir) {
            log.warn("Mensagem {} desistiu após {} tentativas: {}", mensagem.id(), tentativas,
                    erro.getMessage());
        }
    }

    private void marcarEnviada(UUID id) {
        manutencao.jdbc().sql("""
                update mensagem set status = 'ENVIADA', enviada_em = now(), ultimo_erro = null
                 where id = ?
                """)
                .param(id)
                .update();
    }

    private String recortar(String erro) {
        if (erro == null) {
            return null;
        }
        return erro.substring(0, Math.min(erro.length(), 500));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> lerParametros(String bruto) {
        return json.readValue(bruto, Map.class);
    }
}
