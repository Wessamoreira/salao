package br.com.salao.conversacional.internal.application;

import br.com.salao.arquivos.api.TipoDeArquivo;
import java.math.BigDecimal;

/**
 * RT-CNV-003 — transformar áudio em texto.
 *
 * <p>Port, e hoje sem provedor: a pergunta 14 de {@code 13-perguntas-em-aberto} (orçamento, "STT de
 * áudio ficou fora da conta original e não é barato") não foi respondida, e escolher fornecedor é
 * decisão do dono. A implementação padrão diz a verdade — {@link #configurado()} é falso — e os
 * áudios esperam, guardados, até existir quem os transcreva.
 */
public interface Transcritor {

    boolean configurado();

    /**
     * @param tipo o tipo DETECTADO nos bytes, nunca o {@code mime_type} da Meta
     * @throws FalhaTransitoria quando tentar de novo pode resolver
     * @throws FalhaPermanente  quando não pode
     */
    TextoTranscrito transcrever(byte[] audio, TipoDeArquivo tipo);

    /**
     * @param texto   vazio é resultado válido: áudio mudo é áudio mudo, e não falha
     * @param provedor quem transcreveu — é o que permite comparar e trocar de fornecedor depois
     */
    record TextoTranscrito(String texto, String idioma, BigDecimal duracaoSegundos,
                           String provedor) {
    }
}
