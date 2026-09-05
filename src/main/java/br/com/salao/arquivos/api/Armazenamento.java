package br.com.salao.arquivos.api;

import java.time.Duration;
import java.util.Optional;

/**
 * RT-ARQ-001 — onde os bytes moram.
 *
 * <p>Port. A implementação de hoje é local; S3, MinIO ou GCS entram trocando o bean, sem que
 * validação, registro ou permissão mudem.
 *
 * <p>{@link #primeirosBytes} é o método que dá sentido ao resto: com upload direto, a aplicação não
 * vê os bytes passarem — e é lendo o começo do arquivo <em>depois</em> que ela recupera o controle
 * que o presigned URL abriu mão.
 */
public interface Armazenamento {

    /** A URL para o navegador enviar direto, válida por pouco tempo. */
    String urlDeUpload(String chave, Duration validade);

    /** A URL para ler, válida por pouco tempo. */
    String urlDeLeitura(String chave, Duration validade);

    /**
     * Os primeiros bytes do que foi enviado, ou vazio se não há nada na chave.
     *
     * @param quantidade nunca o arquivo inteiro: para saber o formato bastam alguns bytes, e
     *                   baixar megabytes para ler dezesseis seria pagar duas vezes pelo upload
     */
    Optional<byte[]> primeirosBytes(String chave, int quantidade);

    /** Quantos bytes há na chave, ou vazio se não há nada. */
    Optional<Long> tamanho(String chave);

    /** Apaga. Chamado quando a validação recusa — o arquivo não pode ficar no bucket. */
    void apagar(String chave);
}
