package br.com.salao.arquivos.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.arquivos.api.TipoDeArquivo;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Domínio puro: o que o arquivo é, não o que ele diz ser. */
class MagicBytesTest {

    private byte[] bytes(int... valores) {
        byte[] saida = new byte[Math.max(valores.length, MagicBytes.BYTES_NECESSARIOS)];
        for (int i = 0; i < valores.length; i++) {
            saida[i] = (byte) valores[i];
        }
        return saida;
    }

    @Test
    @DisplayName("reconhece os formatos que o salão usa")
    void reconhece_os_permitidos() {
        assertThat(MagicBytes.detectar(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
                .contains(TipoDeArquivo.JPEG);
        assertThat(MagicBytes.detectar(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
                .contains(TipoDeArquivo.PNG);
        assertThat(MagicBytes.detectar(bytes(0x25, 0x50, 0x44, 0x46, 0x2D)))
                .contains(TipoDeArquivo.PDF);
    }

    @Test
    @DisplayName("aceita a foto do iPhone, em todas as marcas que o iOS produz")
    void heic_do_iphone() {
        // Metade das fotos de salão vem de iPhone. Aceitar só "heic" recusaria boa parte delas, e
        // a recusa seria inexplicável para quem enviou.
        for (String marca : new String[] { "heic", "heix", "mif1", "msf1" }) {
            byte[] arquivo = new byte[MagicBytes.BYTES_NECESSARIOS];
            System.arraycopy("....ftyp".getBytes(StandardCharsets.US_ASCII), 0, arquivo, 0, 8);
            System.arraycopy(marca.getBytes(StandardCharsets.US_ASCII), 0, arquivo, 8, 4);

            assertThat(MagicBytes.detectar(arquivo)).as(marca).contains(TipoDeArquivo.HEIC);
        }
    }

    @Test
    @DisplayName("um WAV não passa por WEBP só porque começa com RIFF")
    void riff_nao_e_webp() {
        // Os dois começam com "RIFF". Conferir só o começo aceitaria qualquer container RIFF como
        // imagem — e o arquivo seria servido como image/webp para um navegador que não vai saber
        // o que fazer com ele.
        byte[] wav = new byte[MagicBytes.BYTES_NECESSARIOS];
        System.arraycopy("RIFF----WAVE".getBytes(StandardCharsets.US_ASCII), 0, wav, 0, 12);
        byte[] webp = new byte[MagicBytes.BYTES_NECESSARIOS];
        System.arraycopy("RIFF----WEBP".getBytes(StandardCharsets.US_ASCII), 0, webp, 0, 12);

        assertThat(MagicBytes.detectar(wav)).isEmpty();
        assertThat(MagicBytes.detectar(webp)).contains(TipoDeArquivo.WEBP);
    }

    @Test
    @DisplayName("HTML disfarçado de foto é recusado")
    void html_disfarcado() {
        // O ataque que a validação existe para impedir: `foto.jpg` com Content-Type image/jpeg,
        // servido do domínio do salão, executando script no navegador de quem abrir.
        byte[] html = "<html><script>".getBytes(StandardCharsets.US_ASCII);

        assertThat(MagicBytes.detectar(html)).isEmpty();
    }

    @Test
    @DisplayName("SVG é recusado: é documento com script, não imagem")
    void svg_recusado() {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\">".getBytes(StandardCharsets.UTF_8);

        assertThat(MagicBytes.detectar(svg)).isEmpty();
    }

    @Test
    @DisplayName("ZIP é recusado, mesmo sendo o container de formatos válidos")
    void zip_recusado() {
        // Um .docx é um zip, e um zip bomb também. Nenhum dos dois está na lista.
        assertThat(MagicBytes.detectar(bytes(0x50, 0x4B, 0x03, 0x04))).isEmpty();
    }

    @Test
    @DisplayName("arquivo vazio ou curto demais não vira tipo nenhum")
    void curto_demais() {
        assertThat(MagicBytes.detectar(new byte[0])).isEmpty();
        assertThat(MagicBytes.detectar(new byte[] { (byte) 0xFF, (byte) 0xD8 })).isEmpty();
        assertThat(MagicBytes.detectar(null)).isEmpty();
    }

    @Test
    @DisplayName("o Content-Type devolvido é o DETECTADO")
    void content_type_e_o_detectado() {
        // Devolver o do cliente anularia a validação inteira: um arquivo que passou como JPEG mas
        // é servido como text/html executa no navegador de quem abrir, do domínio do salão.
        assertThat(TipoDeArquivo.JPEG.contentType()).isEqualTo("image/jpeg");
        assertThat(TipoDeArquivo.HEIC.contentType()).isEqualTo("image/heic");
    }
}
