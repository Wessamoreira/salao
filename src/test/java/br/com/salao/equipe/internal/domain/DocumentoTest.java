package br.com.salao.equipe.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.salao.equipe.internal.domain.Documento.Tipo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Domínio puro.
 *
 * <p>Os números válidos usados aqui são gerados pelo próprio algoritmo — não são de pessoas nem de
 * empresas reais. Pôr um CPF de verdade num arquivo de teste versionado seria publicar dado
 * pessoal de alguém no repositório.
 */
class DocumentoTest {

    // Dígitos verificadores calculados pelo mod-11; a base é sequencial e não corresponde a
    // ninguém.
    private static final String CPF_VALIDO = "52998224725";
    private static final String CNPJ_VALIDO = "11222333000181";

    @Test
    @DisplayName("aceita CPF válido, com ou sem máscara")
    void aceita_cpf() {
        assertThat(Documento.de(CPF_VALIDO).tipo()).isEqualTo(Tipo.CPF);
        assertThat(Documento.de("529.982.247-25").digitos()).isEqualTo(CPF_VALIDO);
    }

    @Test
    @DisplayName("aceita CNPJ válido, com ou sem máscara")
    void aceita_cnpj() {
        assertThat(Documento.de(CNPJ_VALIDO).tipo()).isEqualTo(Tipo.CNPJ);
        assertThat(Documento.de("11.222.333/0001-81").digitos()).isEqualTo(CNPJ_VALIDO);
        assertThat(Documento.de(CNPJ_VALIDO).ehPessoaJuridica()).isTrue();
    }

    @Test
    @DisplayName("recusa dígito verificador errado — que é o ponto de conferir")
    void recusa_digito_errado() {
        // Um dígito trocado passa em qualquer validação de tamanho, e só aparece na hora de
        // emitir a nota ou pagar a comissão — meses depois, sem ninguém lembrar do que digitou.
        assertThatThrownBy(() -> Documento.de("52998224726"))
                .isInstanceOf(DocumentoInvalidoException.class)
                .hasMessageContaining("dígito verificador");
        assertThatThrownBy(() -> Documento.de("11222333000182"))
                .isInstanceOf(DocumentoInvalidoException.class);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "11111111111", "00000000000", "99999999999",
            "11111111111111", "00000000000000",
    })
    @DisplayName("recusa dígitos repetidos, que PASSAM no cálculo e não existem")
    void recusa_repetidos(String informado) {
        // É o valor que alguém digita quando precisa preencher o campo e não tem o documento em
        // mãos. O algoritmo aprova; a realidade, não.
        assertThatThrownBy(() -> Documento.de(informado))
                .isInstanceOf(DocumentoInvalidoException.class)
                .hasMessageContaining("iguais");
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = { "123", "1234567890", "123456789012", "abc", "5299822472512345" })
    @DisplayName("recusa o que não tem tamanho de CPF nem de CNPJ")
    void recusa_tamanho_errado(String informado) {
        assertThatThrownBy(() -> Documento.de(informado))
                .isInstanceOf(DocumentoInvalidoException.class);
    }

    @Test
    void recusa_vazio_e_nulo() {
        assertThatThrownBy(() -> Documento.de(null)).isInstanceOf(DocumentoInvalidoException.class);
        assertThatThrownBy(() -> Documento.de("   ")).isInstanceOf(DocumentoInvalidoException.class);
    }

    @Test
    @DisplayName("guarda só dígitos e formata na exibição")
    void guarda_digitos_formata_para_exibir() {
        assertThat(Documento.de("529.982.247-25").formatado()).isEqualTo("529.982.247-25");
        assertThat(Documento.de("11222333000181").formatado()).isEqualTo("11.222.333/0001-81");
    }

    @Test
    @DisplayName("o cálculo vale quando o dígito verificador é zero")
    void trata_digito_zero() {
        // `resto < 2` devolve 0. Sem essa condição, 11 - resto produziria 10 ou 11, que não cabem
        // num dígito — e o erro apareceria SÓ nesses documentos, que são poucos e existem.
        //
        // Os dois números abaixo foram calculados pelo próprio mod-11 para cair nesse caso: um
        // com o primeiro dígito zero, outro com os dois. A primeira versão deste teste usava um
        // número que eu supus ter dígito zero, sem calcular — e ele simplesmente não era válido.
        assertThat(Documento.de("10000000108").tipo()).isEqualTo(Tipo.CPF);
        assertThat(Documento.de("10000003700").digitos()).isEqualTo("10000003700");
    }

    @Test
    @DisplayName("dígitos repetidos na raiz do CNPJ não são repetidos no documento")
    void raiz_repetida_e_valida() {
        // 11.111.111/0001-91 tem raiz toda igual e É um CNPJ válido — a regra dos "todos iguais"
        // olha o documento inteiro, e confundir as duas coisas recusaria empresa real.
        assertThat(Documento.de("11111111000191").tipo()).isEqualTo(Tipo.CNPJ);
    }
}
