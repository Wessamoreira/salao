package br.com.salao.financeiro.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RT-FIN-009 — sugerir qual coluna é qual, e lembrar do que o salão confirmou. */
class MapeamentoDoExtratoTest {

    @Test
    @DisplayName("sugere pelo nome, sem caixa, acento nem \"Nº\"")
    void sugere_pelos_apelidos() {
        var cabecalho = List.of("STONE ID", "Data da Venda", "Valor Bruto", "Valor Líquido",
                "Bandeira", "Nº de Parcelas", "Status");

        var sugerido = MapeamentoDoExtrato.sugerir(cabecalho, Optional.empty(),
                FormatoDeNumero.BRASILEIRO);

        assertThat(sugerido.colunas()).containsEntry(CampoDoExtrato.ID_NA_ADQUIRENTE, "STONE ID")
                .containsEntry(CampoDoExtrato.DATA, "Data da Venda")
                .containsEntry(CampoDoExtrato.VALOR_BRUTO, "Valor Bruto")
                .containsEntry(CampoDoExtrato.VALOR_LIQUIDO, "Valor Líquido")
                .containsEntry(CampoDoExtrato.PARCELAS, "Nº de Parcelas")
                .containsEntry(CampoDoExtrato.SITUACAO, "Status");
        assertThat(sugerido.obrigatoriosFaltando(cabecalho)).isEmpty();
    }

    @Test
    @DisplayName("o apelido genérico \"Valor\" não rouba a coluna \"Valor bruto\"")
    void especifico_antes_do_generico() {
        var cabecalho = List.of("Stone ID", "Data", "Valor", "Valor bruto");

        var sugerido = MapeamentoDoExtrato.sugerir(cabecalho, Optional.empty(),
                FormatoDeNumero.BRASILEIRO);

        assertThat(sugerido.colunas()).containsEntry(CampoDoExtrato.VALOR_BRUTO, "Valor bruto");
    }

    @Test
    @DisplayName("o que o salão confirmou antes vence o apelido; coluna que sumiu cai no apelido")
    void salvo_tem_prioridade() {
        var salvo = new MapeamentoDoExtrato(Map.of(CampoDoExtrato.VALOR_BRUTO, "Total",
                CampoDoExtrato.DATA, "Coluna que sumiu"), FormatoDeNumero.BRASILEIRO);
        var cabecalho = List.of("Stone ID", "Data", "Valor bruto", "Total");

        var sugerido = MapeamentoDoExtrato.sugerir(cabecalho, Optional.of(salvo),
                FormatoDeNumero.BRASILEIRO);

        assertThat(sugerido.colunas()).containsEntry(CampoDoExtrato.VALOR_BRUTO, "Total")
                .containsEntry(CampoDoExtrato.DATA, "Data");
    }

    @Test
    @DisplayName("pelo nome, e não pela posição: mudar a ordem das colunas não troca o campo")
    void indice_pelo_nome() {
        var mapeamento = new MapeamentoDoExtrato(Map.of(CampoDoExtrato.VALOR_BRUTO, "valor bruto"),
                FormatoDeNumero.BRASILEIRO);

        assertThat(mapeamento.indice(CampoDoExtrato.VALOR_BRUTO, List.of("Valor Bruto", "Valor Líquido")))
                .isZero();
        assertThat(mapeamento.indice(CampoDoExtrato.VALOR_BRUTO, List.of("Valor Líquido", "Valor Bruto")))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("sem Stone ID, data ou valor bruto, diz quais faltam")
    void obrigatorios_faltando() {
        var sugerido = MapeamentoDoExtrato.sugerir(List.of("Stone ID", "Qualquer coisa"),
                Optional.empty(), FormatoDeNumero.BRASILEIRO);

        assertThat(sugerido.obrigatoriosFaltando(List.of("Stone ID", "Qualquer coisa")))
                .containsExactly(CampoDoExtrato.DATA, CampoDoExtrato.VALOR_BRUTO);
    }
}
