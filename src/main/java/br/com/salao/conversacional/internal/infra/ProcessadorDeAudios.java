package br.com.salao.conversacional.internal.infra;

import br.com.salao.arquivos.api.IngestaoDeArquivoApi;
import br.com.salao.conversacional.internal.application.FalhaPermanente;
import br.com.salao.conversacional.internal.application.GuardarAudioBaixado;
import br.com.salao.conversacional.internal.application.MidiaDoWhatsApp;
import br.com.salao.conversacional.internal.application.Transcritor;
import br.com.salao.shared.manutencao.ConexaoDeManutencao;
import br.com.salao.shared.tempo.EsperaEntreTentativas;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * RT-CNV-003 — quem tira da fila, baixa e transcreve.
 *
 * <h2>Reserva atômica — RN-CNV-008</h2>
 *
 * <p>Mais de uma instância roda isto. A reserva é um único {@code UPDATE ... RETURNING} com
 * {@code for update skip locked} na subconsulta: o lock e a marcação acontecem no MESMO comando, o
 * que vale mesmo em autocommit. O padrão que parece equivalente — {@code SELECT ... FOR UPDATE SKIP
 * LOCKED} solto e depois o trabalho — solta o lock no fim do SELECT e deixa as duas instâncias
 * pegarem a mesma linha.
 *
 * <p>A reserva expira ({@code reservada_ate}): se a instância morrer no meio, a linha volta sozinha.
 * E a tentativa é contada na RESERVA, e não na falha — uma linha que derruba o processo a cada vez
 * não fica em laço, porque cada reserva gasta uma tentativa.
 *
 * <h2>Nenhuma rede dentro de transação</h2>
 *
 * <p>Reserva: um comando. Download e transcrição: sem transação. Resultado: uma transação curta,
 * dentro do escopo do salão. É o que mantém o pool livre enquanto a Meta e o provedor de STT
 * respondem.
 *
 * <h2>Credencial ausente não é falha</h2>
 *
 * <p>Sem token da Meta, a etapa de baixar nem reserva; sem provedor de STT, a de transcrever
 * também não. As linhas esperam sem gastar tentativa — e fluem no dia em que a credencial existir.
 */
public class ProcessadorDeAudios {

    private static final Logger log = LoggerFactory.getLogger(ProcessadorDeAudios.class);

    /** Por rodada, por etapa. Áudio de salão não chega em rajada; o intervalo é que dá o ritmo. */
    private static final int LOTE = 10;

    /** Mais que o download (60 s) somado ao pior STT razoável. Se passar disso, a instância morreu. */
    private static final Duration RESERVA = Duration.ofMinutes(10);

    private static final String RESERVAR = """
            update transcricao_de_audio t
               set reservada_ate = :reserva, tentativas = t.tentativas + 1
             where t.id in (
                   select id from transcricao_de_audio
                    where status = :etapa
                      and proxima_tentativa_em <= :agora
                      and (reservada_ate is null or reservada_ate < :agora)
                    order by proxima_tentativa_em
                    limit :lote
                      for update skip locked)
            returning t.id, t.estabelecimento_id, t.mensagem_id, t.arquivo_id, t.tentativas
            """;

    private final ConexaoDeManutencao manutencao;
    private final TranscricoesJdbc transcricoes;
    private final MensagensRecebidasJdbc mensagens;
    private final MidiaDoWhatsApp midia;
    private final GuardarAudioBaixado guardar;
    private final IngestaoDeArquivoApi ingestao;
    private final Transcritor transcritor;
    private final Relogio relogio;
    private final MeterRegistry metricas;

    public ProcessadorDeAudios(ConexaoDeManutencao manutencao, TranscricoesJdbc transcricoes,
                               MensagensRecebidasJdbc mensagens, MidiaDoWhatsApp midia,
                               GuardarAudioBaixado guardar, IngestaoDeArquivoApi ingestao,
                               Transcritor transcritor, Relogio relogio, MeterRegistry metricas) {
        this.manutencao = manutencao;
        this.transcricoes = transcricoes;
        this.mensagens = mensagens;
        this.midia = midia;
        this.guardar = guardar;
        this.ingestao = ingestao;
        this.transcritor = transcritor;
        this.relogio = relogio;
        this.metricas = metricas;
    }

    private record Reservada(UUID id, UUID estabelecimentoId, UUID mensagemId, UUID arquivoId,
                             int tentativas) {
    }

    @Scheduled(fixedDelayString = "${app.whatsapp.audio.intervalo:PT30S}",
               initialDelayString = "${app.whatsapp.audio.atraso-inicial:PT20S}")
    public void processar() {
        int feitos = executar();
        if (feitos > 0) {
            log.info("Áudio: {} etapa(s) concluída(s)", feitos);
        }
    }

    /** Exposto para teste e para acionamento manual pelo runbook. */
    public int executar() {
        int feitos = 0;
        if (midia.configurado()) {
            for (Reservada r : reservar("PENDENTE")) {
                feitos += tentar(r, "baixar", this::baixar);
            }
        }
        if (transcritor.configurado()) {
            for (Reservada r : reservar("BAIXADA")) {
                feitos += tentar(r, "transcrever", this::transcrever);
            }
        }
        return feitos;
    }

    private List<Reservada> reservar(String etapa) {
        Instant agora = relogio.agora();
        return manutencao.jdbc().sql(RESERVAR)
                .param("reserva", OffsetDateTime.ofInstant(agora.plus(RESERVA), ZoneOffset.UTC))
                .param("etapa", etapa)
                .param("agora", OffsetDateTime.ofInstant(agora, ZoneOffset.UTC))
                .param("lote", LOTE)
                .query((rs, n) -> new Reservada(
                        rs.getObject("id", UUID.class),
                        rs.getObject("estabelecimento_id", UUID.class),
                        rs.getObject("mensagem_id", UUID.class),
                        rs.getObject("arquivo_id", UUID.class),
                        rs.getInt("tentativas")))
                .list();
    }

    private int tentar(Reservada r, String etapa, Consumer<Reservada> trabalho) {
        try {
            TenantContext.executar(r.estabelecimentoId(), () -> trabalho.accept(r));
            contar(etapa, "ok");
            return 1;
        } catch (FalhaPermanente e) {
            registrar(r, () -> transcricoes.falhar(r.id(), recortar(e.getMessage())));
            contar(etapa, "falhou");
            log.warn("Transcrição {} falhou de vez ao {}: {}", r.id(), etapa, e.getMessage());
        } catch (RuntimeException e) {
            // Transitória, ou qualquer coisa que não sabemos classificar: tentar de novo é o
            // conservador. A tentativa já foi contada na reserva.
            boolean desistir = EsperaEntreTentativas.desistir(r.tentativas());
            Instant proxima = relogio.agora().plus(EsperaEntreTentativas.apos(r.tentativas()));
            String erro = recortar(e.getMessage());
            registrar(r, () -> {
                if (desistir) {
                    transcricoes.falhar(r.id(), erro);
                } else {
                    transcricoes.adiar(r.id(), erro, proxima);
                }
            });
            contar(etapa, desistir ? "falhou" : "adiada");
            log.warn("Transcrição {} {} ao {} (tentativa {}): {}", r.id(),
                    desistir ? "desistiu" : "adiada", etapa, r.tentativas(), e.getMessage());
        }
        return 0;
    }

    private void baixar(Reservada r) {
        var bilhete = mensagens.midiaDe(r.mensagemId())
                .orElseThrow(() -> new FalhaPermanente("a mensagem não tem mídia"));
        // Rede, fora de transação.
        var baixada = midia.baixar(bilhete.midiaId());
        guardar.executar(r.id(), r.mensagemId(), baixada.conteudo(), relogio.agora());
    }

    private void transcrever(Reservada r) {
        // Da NOSSA cópia, e não da Meta: a mídia de lá expira, e a retentativa do STT não pode
        // depender dela. É também o que evita baixar duas vezes.
        var audio = ingestao.conteudo(r.arquivoId())
                .orElseThrow(() -> new FalhaPermanente("o áudio guardado não foi encontrado"));
        var resultado = transcritor.transcrever(audio.bytes(), audio.tipo());
        transcricoes.concluir(r.id(), resultado, relogio.agora());
    }

    /** A gravação da falha também pode falhar (banco fora). Aí a reserva vence e a linha volta. */
    private void registrar(Reservada r, Runnable gravacao) {
        try {
            TenantContext.executar(r.estabelecimentoId(), gravacao);
        } catch (RuntimeException e) {
            log.error("Não foi possível registrar o desfecho da transcrição {}: {}", r.id(),
                    e.getMessage());
        }
    }

    private void contar(String etapa, String desfecho) {
        metricas.counter("conversacional.audio", "etapa", etapa, "desfecho", desfecho).increment();
    }

    /** Mensagem de integrador às vezes traz o corpo da resposta; a coluna não é log. */
    private static String recortar(String erro) {
        if (erro == null) {
            return null;
        }
        return erro.substring(0, Math.min(erro.length(), 500));
    }
}
