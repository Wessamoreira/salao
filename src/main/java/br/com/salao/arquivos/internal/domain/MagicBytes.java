package br.com.salao.arquivos.internal.domain;

import br.com.salao.arquivos.api.TipoDeArquivo;
import java.util.Optional;

/**
 * RT-ARQ-001 — o que o arquivo <strong>é</strong>, não o que ele diz ser.
 *
 * <h2>Por que extensão e Content-Type não servem</h2>
 *
 * <p>Os dois são escolhidos por quem envia. {@code foto.jpg} com {@code Content-Type: image/jpeg}
 * pode ser um HTML com script, um executável ou um arquivo compactado — e a exigência está escrita
 * em {@code 05-seguranca}: "não confiar em extensão nem em Content-Type".
 *
 * <p>Os primeiros bytes, não. Eles são o formato de verdade, porque é por eles que qualquer
 * programa decide como ler o arquivo.
 *
 * <h2>O que isto NÃO resolve</h2>
 *
 * <p><strong>Arquivo poliglota.</strong> É possível construir um arquivo que seja um JPEG válido
 * <em>e</em> um HTML válido: os primeiros bytes são de JPEG, e o navegador que o receba como
 * {@code text/html} executa o script que vem depois.
 *
 * <p>A defesa contra isso não está aqui — está em <em>servir</em> o arquivo com o
 * {@code Content-Type} <strong>detectado</strong> e com {@code Content-Disposition: attachment},
 * nunca com o que o cliente declarou. Magic bytes dizem o que entrou; o cabeçalho de saída decide
 * o que o navegador faz com isso. Precisa dos dois.
 */
public final class MagicBytes {

    /** Bytes bastam para todas as assinaturas conhecidas; ler mais é tráfego à toa. */
    public static final int BYTES_NECESSARIOS = 16;

    private MagicBytes() {
    }

    /** O tipo, se for um dos permitidos. Vazio significa "não é nenhum deles". */
    public static Optional<TipoDeArquivo> detectar(byte[] inicio) {
        if (inicio == null || inicio.length < 4) {
            return Optional.empty();
        }
        if (comeca(inicio, 0xFF, 0xD8, 0xFF)) {
            return Optional.of(TipoDeArquivo.JPEG);
        }
        if (comeca(inicio, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of(TipoDeArquivo.PNG);
        }
        if (comeca(inicio, 0x25, 0x50, 0x44, 0x46)) {
            return Optional.of(TipoDeArquivo.PDF);
        }
        // WEBP e HEIC têm a marca depois de um cabeçalho de container: os quatro primeiros bytes
        // são "RIFF" (tamanho variável a seguir) ou o tamanho da caixa. Conferir só o começo
        // aceitaria qualquer RIFF — inclusive um WAV — como imagem.
        if (comeca(inicio, 0x52, 0x49, 0x46, 0x46) && em(inicio, 8, 0x57, 0x45, 0x42, 0x50)) {
            return Optional.of(TipoDeArquivo.WEBP);
        }
        if (em(inicio, 4, 0x66, 0x74, 0x79, 0x70) && ehHeic(inicio)) {
            return Optional.of(TipoDeArquivo.HEIC);
        }

        // ---- RT-CNV-003: áudio. Detectar não é aceitar: quem decide se o tipo cabe no dono é
        // TiposPorDono.
        if (comeca(inicio, 0x4F, 0x67, 0x67, 0x53)) {                 // "OggS"
            return Optional.of(TipoDeArquivo.OGG);
        }
        if (comeca(inicio, 0x23, 0x21, 0x41, 0x4D, 0x52)) {           // "#!AMR"
            return Optional.of(TipoDeArquivo.AMR);
        }
        if (comeca(inicio, 0x49, 0x44, 0x33) || ehQuadroMp3(inicio)) { // "ID3", ou quadro MPEG
            return Optional.of(TipoDeArquivo.MP3);
        }
        // Só a marca "M4A ". "isom" e "mp42" são o contêiner MP4 genérico, que também é vídeo —
        // aceitá-los como áudio seria deixar entrar um vídeo pela porta das notas de voz.
        if (em(inicio, 4, 0x66, 0x74, 0x79, 0x70) && em(inicio, 8, 'M', '4', 'A', ' ')) {
            return Optional.of(TipoDeArquivo.M4A);
        }
        return Optional.empty();
    }

    /**
     * Um quadro MPEG de áudio sem cabeçalho ID3.
     *
     * <p>Os 11 bits de sincronia não bastam: o AAC cru (ADTS) começa igual. A diferença está na
     * camada — {@code 00} é reservada no MPEG e é justamente o que o ADTS usa. Sem esta conferência,
     * um AAC passaria como MP3 e seria servido com o {@code Content-Type} errado.
     */
    private static boolean ehQuadroMp3(byte[] inicio) {
        int primeiro = inicio[0] & 0xFF;
        int segundo = inicio[1] & 0xFF;
        if (primeiro != 0xFF || (segundo & 0xE0) != 0xE0) {
            return false;
        }
        int versao = (segundo >> 3) & 0x03;   // 01 é reservado
        int camada = (segundo >> 1) & 0x03;   // 00 é reservado — e é o que o ADTS usa
        return versao != 0x01 && camada != 0x00;
    }

    /**
     * A marca de HEIC vem logo depois de {@code ftyp}, e são várias.
     *
     * <p>{@code heic} e {@code heix} são a foto do iPhone; {@code mif1} e {@code msf1} são o
     * contêiner genérico que o iOS também produz. Aceitar só {@code heic} recusaria metade das
     * fotos que chegam de um iPhone — e a recusa seria inexplicável para quem enviou.
     */
    private static boolean ehHeic(byte[] inicio) {
        for (String marca : new String[] { "heic", "heix", "mif1", "msf1", "hevc", "heim" }) {
            if (em(inicio, 8, marca.charAt(0), marca.charAt(1), marca.charAt(2), marca.charAt(3))) {
                return true;
            }
        }
        return false;
    }

    private static boolean comeca(byte[] dados, int... esperados) {
        return em(dados, 0, esperados);
    }

    private static boolean em(byte[] dados, int posicao, int... esperados) {
        if (dados.length < posicao + esperados.length) {
            return false;
        }
        for (int i = 0; i < esperados.length; i++) {
            if ((dados[posicao + i] & 0xFF) != (esperados[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }
}
