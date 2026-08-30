package br.com.salao.cliente.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Domínio puro. Cada caso aqui é uma forma real de alguém digitar um telefone com a cliente
 * na frente — não variação inventada para inflar cobertura.
 */
class TelefoneTest {

    private static final String PAIS = "55";
    private static final String DDD = "11";

    private Telefone de(String informado) {
        return Telefone.de(informado, PAIS, DDD);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "(11) 98765-4321",   // colado do WhatsApp
            "11987654321",       // digitado corrido
            "+55 11 98765-4321", // de quem já sabe o formato
            "+5511987654321",
            "0055 11 98765 4321", // agenda antiga, discagem internacional
            "11 9 8765 4321",     // com o nono dígito separado
            " (11)98765.4321 ",   // com ponto e espaço sobrando
    })
    @DisplayName("o mesmo número, escrito de sete formas, vira um só registro")
    void normaliza_todas_as_formas(String informado) {
        // Se cada forma virasse uma linha diferente, a busca por telefone deixaria de
        // funcionar exatamente para quem mais precisa dela: quem atende o telefone tocando.
        assertThat(de(informado).e164()).isEqualTo("5511987654321");
    }

    @Test
    @DisplayName("sem DDD, completa com o do estabelecimento")
    void completa_o_ddd() {
        // Quem atende anota "98765-4321": o DDD é óbvio para quem está ali. Exigi-lo é
        // fricção que faz a recepção inventar dado.
        assertThat(de("98765-4321").e164()).isEqualTo("5511987654321");
        assertThat(de("987654321").e164()).isEqualTo("5511987654321");
    }

    @Test
    @DisplayName("fixo de oito dígitos é válido — o nono dígito NÃO é inventado")
    void nao_inventa_o_nono_digito() {
        // Adivinhar transformaria um número certo em errado sem ninguém perceber, e o
        // sintoma apareceria só quando a cliente não atendesse a confirmação.
        assertThat(de("3333-4444").e164()).isEqualTo("551133334444");
        assertThat(de("(11) 3333-4444").e164()).isEqualTo("551133334444");
    }

    @Test
    @DisplayName("sem DDD e sem padrão configurado, recusa em vez de chutar")
    void recusa_sem_ddd_padrao() {
        assertThatThrownBy(() -> Telefone.de("98765-4321", PAIS, null))
                .isInstanceOf(TelefoneInvalidoException.class)
                .hasMessageContaining("DDD padrão");
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = { "1234", "abc", "9", "1234567890123456", "----" })
    @DisplayName("recusa o que não é telefone")
    void recusa_invalidos(String informado) {
        assertThatThrownBy(() -> de(informado)).isInstanceOf(TelefoneInvalidoException.class);
    }

    @Test
    void recusa_vazio_e_nulo() {
        assertThatThrownBy(() -> de(null)).isInstanceOf(TelefoneInvalidoException.class);
        assertThatThrownBy(() -> de("   ")).isInstanceOf(TelefoneInvalidoException.class);
    }

    @Test
    @DisplayName("expõe os quatro últimos, que é como a recepção procura")
    void ultimos_quatro() {
        assertThat(de("(11) 98765-4321").ultimosQuatro()).isEqualTo("4321");
    }

    @Test
    @DisplayName("formata para exibir, mas guarda só dígitos")
    void formata_para_exibicao() {
        // Formatar na gravação faria a busca por sufixo depender de como cada um digitou.
        assertThat(de("11987654321").formatado()).isEqualTo("(11) 98765-4321");
        assertThat(de("1133334444").formatado()).isEqualTo("(11) 3333-4444");
    }

    @Test
    @DisplayName("DDD de outro estado é respeitado, não substituído pelo padrão")
    void respeita_ddd_informado() {
        // Cliente de fora existe. Sobrescrever pelo padrão do salão daria um número que
        // parece certo e não completa a chamada.
        assertThat(de("(21) 99999-8888").e164()).isEqualTo("5521999998888");
    }
}
