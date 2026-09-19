package br.com.salao.shared.cripto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RT-IAM-005 / RT-CNV-008 — o cofre de campo, e a impressão com chave. */
class CofreDeCampoTest {

    private final CofreDeCampo cofre = new CofreDeCampo(CofreDeCampo.novaChaveBase64());

    @Test
    @DisplayName("a impressão é a mesma para o mesmo valor, e tem 32 bytes")
    void impressao_deterministica() {
        assertThat(cofre.impressao("salao:5511987654321"))
                .hasSize(32)
                .isEqualTo(cofre.impressao("salao:5511987654321"));
    }

    @Test
    @DisplayName("valores diferentes, impressões diferentes")
    void impressao_distingue() {
        assertThat(cofre.impressao("salao:5511987654321"))
                .isNotEqualTo(cofre.impressao("salao:5511987654322"));
    }

    @Test
    @DisplayName("sem a chave não se refaz: outra chave dá outra impressão")
    void impressao_depende_da_chave() {
        var outro = new CofreDeCampo(CofreDeCampo.novaChaveBase64());

        assertThat(outro.impressao("salao:5511987654321"))
                .isNotEqualTo(cofre.impressao("salao:5511987654321"));
    }

    @Test
    @DisplayName("não é um SHA-256 simples, que se desfaz por força bruta sobre os telefones possíveis")
    void impressao_nao_e_hash_simples() throws NoSuchAlgorithmException {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest("salao:5511987654321".getBytes(StandardCharsets.UTF_8));

        assertThat(cofre.impressao("salao:5511987654321")).isNotEqualTo(hash);
    }

    @Test
    @DisplayName("a impressão não mexe na cifragem: o que se cifra continua voltando")
    void cifragem_intacta() {
        assertThat(cofre.decifrar(cofre.cifrar("segredo totp"))).isEqualTo("segredo totp");
    }
}
