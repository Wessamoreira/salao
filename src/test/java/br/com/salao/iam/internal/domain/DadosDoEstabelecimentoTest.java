package br.com.salao.iam.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.salao.iam.api.BaseDeComissao;
import br.com.salao.iam.api.PeriodicidadeDeFechamento;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Domínio puro: sem Spring, sem banco, sem contexto. */
class DadosDoEstabelecimentoTest {

    private DadosDoEstabelecimento comFuso(String fuso) {
        return DadosDoEstabelecimento.comFuso("Salão Teste", "12345678000199", fuso, "BRL",
                BaseDeComissao.BRUTO, false, PeriodicidadeDeFechamento.MENSAL, "11", null);
    }

    @Test
    void aceita_fuso_iana() {
        assertThat(comFuso("America/Sao_Paulo").fuso())
                .isEqualTo(ZoneId.of("America/Sao_Paulo"));
    }

    @Test
    @DisplayName("recusa offset fixo, que não conhece horário de verão")
    void recusa_offset_fixo() {
        // Se o Brasil voltar a adotar horário de verão, um estabelecimento gravado como -03:00
        // teria a agenda inteira deslocada uma hora, e ninguém perceberia até um cliente chegar
        // no horário errado.
        assertThatThrownBy(() -> comFuso("-03:00"))
                .isInstanceOf(DadosDoEstabelecimentoInvalidosException.class)
                .hasMessageContaining("America/Sao_Paulo");
    }

    @Test
    void recusa_fuso_inexistente() {
        assertThatThrownBy(() -> comFuso("America/Nao_Existe"))
                .isInstanceOf(DadosDoEstabelecimentoInvalidosException.class);
    }

    @Test
    void recusa_nome_vazio() {
        assertThatThrownBy(() -> DadosDoEstabelecimento.comFuso("  ", null, "America/Sao_Paulo",
                "BRL", BaseDeComissao.BRUTO, false, PeriodicidadeDeFechamento.MENSAL, "11", null))
                .isInstanceOf(DadosDoEstabelecimentoInvalidosException.class);
    }

    @Test
    void recusa_ddd_padrao_com_zero_a_mais() {
        // "011" faria todo telefone normalizado nascer com um zero extra e nunca completar a
        // chamada — um erro que só apareceria quando alguém tentasse ligar para a cliente.
        assertThatThrownBy(() -> DadosDoEstabelecimento.comFuso("Salão", null, "America/Sao_Paulo",
                "BRL", BaseDeComissao.BRUTO, false, PeriodicidadeDeFechamento.MENSAL, "011", null))
                .isInstanceOf(DadosDoEstabelecimentoInvalidosException.class);
    }

    @Test
    void aceita_sem_ddd_padrao() {
        assertThat(DadosDoEstabelecimento.comFuso("Salão", null, "America/Sao_Paulo", "BRL",
                BaseDeComissao.BRUTO, false, PeriodicidadeDeFechamento.MENSAL, null, null).dddPadrao())
                .isNull();
    }

    @Test
    void recusa_moeda_desconhecida() {
        assertThatThrownBy(() -> DadosDoEstabelecimento.comFuso("Salão", null, "America/Sao_Paulo",
                "XYZ", BaseDeComissao.BRUTO, false, PeriodicidadeDeFechamento.MENSAL, "11", null))
                .isInstanceOf(DadosDoEstabelecimentoInvalidosException.class);
    }

    // ------------------------------------------------------------------ RT-CNV-008: contato para clientes

    private static DadosDoEstabelecimento comContato(String contato) {
        return DadosDoEstabelecimento.comFuso("Salão", null, "America/Sao_Paulo", "BRL",
                BaseDeComissao.BRUTO, false, PeriodicidadeDeFechamento.MENSAL, "11", contato);
    }

    @Test
    @DisplayName("contato: telefone com máscara ou sem DDD sai normalizado, como todo telefone")
    void contato_telefone_normalizado() {
        assertThat(comContato("(11) 3456-7890").contatoParaClientes()).isEqualTo("551134567890");
        assertThat(comContato("3456-7890").contatoParaClientes()).isEqualTo("551134567890");
        assertThat(comContato("+55 11 98765-4321").contatoParaClientes()).isEqualTo("5511987654321");
    }

    @Test
    @DisplayName("contato: link https fica como foi escrito")
    void contato_link_https() {
        assertThat(comContato("  https://studio.exemplo/agendar ").contatoParaClientes())
                .isEqualTo("https://studio.exemplo/agendar");
    }

    @Test
    @DisplayName("contato: http é recusado — é o link que a cliente toca")
    void contato_http_recusado() {
        // A mensagem específica: a genérica ("telefone ou link https://") também contém "https://",
        // e sem esta asserção a recusa de http poderia sumir e o teste continuaria verde.
        assertThatThrownBy(() -> comContato("http://studio.exemplo"))
                .isInstanceOf(DadosDoEstabelecimentoInvalidosException.class)
                .hasMessageContaining("precisa começar com https://");
    }

    @Test
    @DisplayName("contato: o que não é telefone nem link é recusado dizendo o que se espera")
    void contato_invalido() {
        assertThatThrownBy(() -> comContato("www.studio"))
                .isInstanceOf(DadosDoEstabelecimentoInvalidosException.class)
                .hasMessageContaining("telefone ou um link");
        assertThatThrownBy(() -> comContato("https://localhost/agenda"))
                .isInstanceOf(DadosDoEstabelecimentoInvalidosException.class);
        assertThatThrownBy(() -> comContato("https://" + "a".repeat(200) + ".com"))
                .isInstanceOf(DadosDoEstabelecimentoInvalidosException.class)
                .hasMessageContaining("200");
    }

    @Test
    @DisplayName("contato: vazio vale — o aviso sai sem a linha do contato")
    void contato_vazio() {
        assertThat(comContato("   ").contatoParaClientes()).isNull();
        assertThat(comContato(null).contatoParaClientes()).isNull();
    }
}
