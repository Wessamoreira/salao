package br.com.salao.conversacional.internal.application;

import br.com.salao.arquivos.api.ArquivoResumo;
import br.com.salao.arquivos.api.IngestaoDeArquivoApi;
import br.com.salao.arquivos.api.StatusDoArquivo;
import br.com.salao.conversacional.internal.infra.TranscricoesJdbc;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CNV-003 — guardar o áudio e avançar a fila, no mesmo commit.
 *
 * <h2>Uma transação curta, depois da rede</h2>
 *
 * <p>O download já aconteceu, fora de transação. Aqui só entra o que precisa ser atômico: o arquivo
 * guardado e a linha em {@code BAIXADA}. Separados, uma falha entre os dois deixaria o áudio guardado
 * e a linha pendente — e a próxima tentativa baixaria e guardaria de novo.
 *
 * <h2>Recusa não lança — lição de RN-ARQ-003</h2>
 *
 * <p>Se os bytes não forem áudio, a ingestão devolve o arquivo {@code RECUSADO}. Lançar daqui faria
 * rollback do próprio registro da recusa, exatamente o defeito que RT-ARQ-001 encontrou. Então a
 * linha é dada como {@code FALHOU} no mesmo commit, e o método DEVOLVE o desfecho.
 */
public class GuardarAudioBaixado {

    private final TranscricoesJdbc transcricoes;
    private final IngestaoDeArquivoApi ingestao;

    public GuardarAudioBaixado(TranscricoesJdbc transcricoes, IngestaoDeArquivoApi ingestao) {
        this.transcricoes = transcricoes;
        this.ingestao = ingestao;
    }

    public enum Desfecho { GUARDADO, RECUSADO, JA_FEITO }

    @Transactional
    public Desfecho executar(UUID transcricaoId, UUID mensagemId, byte[] audio, Instant agora) {
        // A reserva pode ter vencido enquanto a rede demorava, e outra instância ter terminado. O lock
        // da linha, dentro desta transação, é o que impede dois arquivos para o mesmo áudio.
        if (!transcricoes.travarPendente(transcricaoId)) {
            return Desfecho.JA_FEITO;
        }
        ArquivoResumo arquivo = ingestao.guardar("MENSAGEM", mensagemId,
                "audio-" + mensagemId, audio);
        if (arquivo.status() != StatusDoArquivo.VALIDADO) {
            transcricoes.falhar(transcricaoId, "áudio recusado: " + arquivo.motivoRecusa());
            return Desfecho.RECUSADO;
        }
        transcricoes.marcarBaixada(transcricaoId, arquivo.id(), agora);
        return Desfecho.GUARDADO;
    }
}
