package br.com.salao.iam.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.salao.iam.api.BaseDeComissao;
import br.com.salao.iam.api.PeriodicidadeDeFechamento;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Domínio puro: sem Spring, sem banco, sem contexto. */
class NovoEstabelecimentoTest {

    private NovoEstabelecimento comFuso(String fuso) {
        return NovoEstabelecimento.comFuso("Salão Teste", "12345678000199", fuso, "BRL",
                BaseDeComissao.BRUTO, false, PeriodicidadeDeFechamento.MENSAL);
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
        assertThatThrownBy(() -> NovoEstabelecimento.comFuso("  ", null, "America/Sao_Paulo",
                "BRL", BaseDeComissao.BRUTO, false, PeriodicidadeDeFechamento.MENSAL))
                .isInstanceOf(DadosDoEstabelecimentoInvalidosException.class);
    }

    @Test
    void recusa_moeda_desconhecida() {
        assertThatThrownBy(() -> NovoEstabelecimento.comFuso("Salão", null, "America/Sao_Paulo",
                "XYZ", BaseDeComissao.BRUTO, false, PeriodicidadeDeFechamento.MENSAL))
                .isInstanceOf(DadosDoEstabelecimentoInvalidosException.class);
    }
}
