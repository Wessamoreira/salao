package br.com.salao.arquivos.api;

/**
 * RT-CNV-003 — os bytes de um arquivo validado, com o tipo que os bytes provaram ter.
 *
 * <p>O tipo vai junto porque quem relê precisa dele — o transcritor precisa saber se é Ogg ou MP3 —,
 * e o que se entrega é o DETECTADO na ingestão, nunca o {@code mime_type} que a Meta declarou.
 */
public record ConteudoDeArquivo(TipoDeArquivo tipo, byte[] bytes) {
}
