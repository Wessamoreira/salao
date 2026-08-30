package br.com.salao.cliente.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.cliente.internal.domain.TermoDeBusca.Insuficiente;
import br.com.salao.cliente.internal.domain.TermoDeBusca.PorNome;
import br.com.salao.cliente.internal.domain.TermoDeBusca.PorTelefone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Domínio puro: o que a recepção digita, e o que o sistema entende disso. */
class TermoDeBuscaTest {

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = { "4321", "98765-4321", "(11) 9876", "11 9876 5432", "987" })
    @DisplayName("qualquer dígito significa telefone")
    void digito_significa_telefone(String digitado) {
        // Ninguém escolhe num seletor se está buscando por nome ou telefone: a pessoa só digita,
        // com o telefone tocando na outra mão.
        assertThat(TermoDeBusca.de(digitado)).isInstanceOf(PorTelefone.class);
    }

    @Test
    @DisplayName("a máscara é descartada, sobram os dígitos")
    void extrai_os_digitos() {
        assertThat(TermoDeBusca.de("(11) 98765-4321"))
                .isEqualTo(new PorTelefone("11987654321"));
        assertThat(TermoDeBusca.de(" 4321 ")).isEqualTo(new PorTelefone("4321"));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = { "ju", "Maria", "silva", "José", "dona zilda" })
    @DisplayName("sem dígito é nome")
    void sem_digito_e_nome(String digitado) {
        assertThat(TermoDeBusca.de(digitado)).isInstanceOf(PorNome.class);
    }

    @Test
    @DisplayName("termo curto não é erro, é 'ainda digitando'")
    void curto_demais_nao_e_erro() {
        // Devolver 422 aqui faria a tela piscar um erro a cada tecla.
        assertThat(TermoDeBusca.de("j")).isInstanceOf(Insuficiente.class);
        assertThat(TermoDeBusca.de("12")).isInstanceOf(Insuficiente.class);
        assertThat(TermoDeBusca.de("")).isInstanceOf(Insuficiente.class);
        assertThat(TermoDeBusca.de("   ")).isInstanceOf(Insuficiente.class);
        assertThat(TermoDeBusca.de(null)).isInstanceOf(Insuficiente.class);
    }

    @Test
    @DisplayName("dois dígitos pedem dígitos, duas letras já bastam")
    void limites_diferentes_por_tipo() {
        // Telefone exige mais porque dois dígitos casariam com metade do salão; duas letras já
        // estreitam bastante, e quem digita "ju" quer ver a lista das Ju agora.
        assertThat(TermoDeBusca.de("ju")).isInstanceOf(PorNome.class);
        assertThat(TermoDeBusca.de("43")).isInstanceOf(Insuficiente.class);
        assertThat(TermoDeBusca.de("432")).isInstanceOf(PorTelefone.class);
    }

    @Test
    @DisplayName("espaço das pontas não conta como letra")
    void espaco_nao_conta() {
        assertThat(TermoDeBusca.de("  j  ")).isInstanceOf(Insuficiente.class);
        assertThat(TermoDeBusca.de("  ju  ")).isEqualTo(new PorNome("ju"));
    }
}
