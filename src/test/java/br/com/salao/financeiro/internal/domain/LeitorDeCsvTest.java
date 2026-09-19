package br.com.salao.financeiro.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RT-FIN-009 — ler o CSV de um portal sem supor quase nada sobre ele. */
class LeitorDeCsvTest {

    private static byte[] utf8(String texto) {
        return texto.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("ponto e vírgula, com aspas em volta do valor")
    void ponto_e_virgula() {
        var tabela = LeitorDeCsv.ler(utf8("Stone ID;Data;Valor bruto\n1;01/09/2026 10:00;\"1.234,56\"\n"));

        assertThat(tabela.separador()).isEqualTo(';');
        assertThat(tabela.cabecalho()).containsExactly("Stone ID", "Data", "Valor bruto");
        assertThat(tabela.registros()).singleElement().satisfies(r ->
                assertThat(r.campos()).containsExactly("1", "01/09/2026 10:00", "1.234,56"));
    }

    @Test
    @DisplayName("o BOM do UTF-8 não gruda no nome da primeira coluna")
    void bom() {
        var tabela = LeitorDeCsv.ler(utf8("﻿Stone ID;Valor\n1;10,00\n"));

        assertThat(tabela.cabecalho().get(0)).isEqualTo("Stone ID");
        assertThat(tabela.codificacao()).isEqualTo("UTF-8");
    }

    @Test
    @DisplayName("Windows-1252, que é o que o Excel brasileiro grava: \"Situação\" não vira lixo")
    void windows_1252() {
        byte[] bytes = "Stone ID;Situação\n1;Aprovada\n".getBytes(Charset.forName("windows-1252"));

        var tabela = LeitorDeCsv.ler(bytes);

        assertThat(tabela.codificacao()).isEqualTo("windows-1252");
        assertThat(tabela.cabecalho()).containsExactly("Stone ID", "Situação");
    }

    @Test
    @DisplayName("vírgula como separador, com vírgula, quebra de linha e aspas dentro do campo")
    void virgula_e_aspas() {
        var tabela = LeitorDeCsv.ler(utf8(
                "id,descricao,valor\r\n1,\"corte, escova\",10.00\r\n2,\"linha\nquebrada \"\"x\"\"\",20.00\r\n"));

        assertThat(tabela.separador()).isEqualTo(',');
        assertThat(tabela.registros()).hasSize(2);
        assertThat(tabela.registros().get(0).campos()).containsExactly("1", "corte, escova", "10.00");
        assertThat(tabela.registros().get(1).campos().get(1)).isEqualTo("linha\nquebrada \"x\"");
    }

    @Test
    @DisplayName("título e período antes do cabeçalho ficam para trás, e a linha é a do arquivo")
    void titulo_antes_do_cabecalho() {
        var tabela = LeitorDeCsv.ler(utf8("""
                Relatório de vendas
                Período: 01/09/2026 a 30/09/2026

                Stone ID;Data;Valor bruto
                1;01/09/2026 10:00;10,00

                2;02/09/2026 11:00;20,00
                """));

        assertThat(tabela.cabecalho()).containsExactly("Stone ID", "Data", "Valor bruto");
        assertThat(tabela.registros()).extracting(LeitorDeCsv.Registro::linha).containsExactly(5, 7);
    }

    @Test
    @DisplayName("planilha do Excel é recusada dizendo o que fazer")
    void excel_recusado() {
        byte[] xlsx = { 'P', 'K', 3, 4, 0, 0 };
        byte[] xls = { (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 0 };

        assertThatThrownBy(() -> LeitorDeCsv.ler(xlsx))
                .isInstanceOf(ArquivoDeExtratoInvalidoException.class).hasMessageContaining("Excel");
        assertThatThrownBy(() -> LeitorDeCsv.ler(xls))
                .isInstanceOf(ArquivoDeExtratoInvalidoException.class).hasMessageContaining("Excel")
                .hasMessageContaining(".xls");
    }

    @Test
    @DisplayName("vazio, ou texto sem colunas, é recusado")
    void sem_colunas() {
        assertThatThrownBy(() -> LeitorDeCsv.ler(new byte[0]))
                .isInstanceOf(ArquivoDeExtratoInvalidoException.class).hasMessageContaining("vazio");
        assertThatThrownBy(() -> LeitorDeCsv.ler(utf8("só um texto qualquer\n")))
                .isInstanceOf(ArquivoDeExtratoInvalidoException.class);
    }

    @Test
    @DisplayName("mais vendas que o teto é recusado pedindo um período menor")
    void grande_demais() {
        var texto = new StringBuilder("id;valor\n");
        for (int i = 0; i <= LeitorDeCsv.REGISTROS_MAXIMOS + 60; i++) {
            texto.append(i).append(";10,00\n");
        }

        assertThatThrownBy(() -> LeitorDeCsv.ler(utf8(texto.toString())))
                .isInstanceOf(ArquivoDeExtratoInvalidoException.class)
                .hasMessageContaining("período menor");
    }

    @Test
    @DisplayName("linha em branco no meio não é venda")
    void linha_em_branco() {
        var tabela = LeitorDeCsv.ler(utf8("id;valor\n1;10,00\n;\n2;20,00\n"));

        assertThat(tabela.registros()).extracting(r -> r.campos().get(0)).isEqualTo(List.of("1", "2"));
    }
}
