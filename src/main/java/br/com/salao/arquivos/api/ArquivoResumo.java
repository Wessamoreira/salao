package br.com.salao.arquivos.api;

import java.time.Instant;
import java.util.UUID;

/** RT-ARQ-001 — o arquivo como as telas e os outros módulos o veem. */
public record ArquivoResumo(
        UUID id,
        UUID donoId,
        String donoTipo,
        String chave,
        String nomeOriginal,
        TipoDeArquivo tipo,
        Long tamanhoBytes,
        StatusDoArquivo status,
        String motivoRecusa,
        Instant criadoEm) {
}
