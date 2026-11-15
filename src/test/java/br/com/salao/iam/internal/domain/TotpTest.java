package br.com.salao.iam.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Vetores de teste oficiais da RFC 6238, apêndice B.
 *
 * <p>É o que transforma "acho que implementei certo" em corretude demonstrada — e é a razão de a
 * implementação estar no projeto em vez de vir de uma dependência.
 */
class TotpTest {

    /** Segredo dos vetores da RFC: os 20 bytes ASCII "12345678901234567890". */
    private static final String SEGREDO_DA_RFC =
            Totp.base32("12345678901234567890".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

    @ParameterizedTest(name = "t={0} → {1}")
    @CsvSource({
            "59,          287082",
            "1111111109,  081804",
            "1111111111,  050471",
            "1234567890,  005924",
            "2000000000,  279037",
            "20000000000, 353130",
    })
    @DisplayName("bate com os vetores oficiais da RFC 6238")
    void vetores_da_rfc(long segundos, String esperado) {
        long contador = Totp.contadorDe(Instant.ofEpochSecond(segundos));

        assertThat(Totp.gerar(SEGREDO_DA_RFC, contador)).isEqualTo(esperado);
    }

    @Test
    void base32_ida_e_volta() {
        String segredo = Totp.novoSegredo();

        assertThat(Totp.base32(Totp.deBase32(segredo))).isEqualTo(segredo);
    }

    @Test
    @DisplayName("aceita a janela anterior e a seguinte, para relógio dessincronizado")
    void tolera_uma_janela() {
        String segredo = Totp.novoSegredo();
        long agora = Totp.contadorDe(Instant.now());

        assertThat(Totp.conferir(segredo, Totp.gerar(segredo, agora - 1), agora, 1)).isEqualTo(agora - 1);
        assertThat(Totp.conferir(segredo, Totp.gerar(segredo, agora), agora, 1)).isEqualTo(agora);
        assertThat(Totp.conferir(segredo, Totp.gerar(segredo, agora + 1), agora, 1)).isEqualTo(agora + 1);
    }

    @Test
    @DisplayName("recusa fora da tolerância, código errado e formato inválido")
    void recusa_o_que_deve() {
        String segredo = Totp.novoSegredo();
        long agora = Totp.contadorDe(Instant.now());

        assertThat(Totp.conferir(segredo, Totp.gerar(segredo, agora - 5), agora, 1)).isEqualTo(-1);
        assertThat(Totp.conferir(segredo, "000000", agora, 1)).isIn(-1L, agora);
        assertThat(Totp.conferir(segredo, "12345", agora, 1)).isEqualTo(-1);
        assertThat(Totp.conferir(segredo, null, agora, 1)).isEqualTo(-1);
    }

    @Test
    @DisplayName("o endereço do QR leva espaço como %20 — com '+', o celular mostrava 'Salao+Wesley'")
    void enderecoDoQrCodificaEspacoComoPercentVinte() {
        String uri = Totp.uriDeConfiguracao("Salao Wesley", "dono@salao.local", "JBSWY3DPEHPK3PXP");

        assertThat(uri).isEqualTo("otpauth://totp/Salao%20Wesley%3Adono%40salao.local"
                + "?secret=JBSWY3DPEHPK3PXP&issuer=Salao%20Wesley&digits=6&period=30");
        assertThat(uri).doesNotContain("+");
    }

    @Test
    @DisplayName("acento no nome do salão vai em UTF-8, e o autenticador mostra 'Salão' certo")
    void acentoVaiEmUtf8() {
        assertThat(Totp.uriDeConfiguracao("Salão", "a@b.c", "X"))
                .startsWith("otpauth://totp/Sal%C3%A3o%3Aa%40b.c?secret=X&issuer=Sal%C3%A3o");
    }
}

