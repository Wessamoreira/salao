package br.com.salao.financeiro.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RT-FIN-009 — cada linha vira venda lida, ignorada ou recusada, e nunca um valor errado. */
class InterpretadorDoExtratoTest {

    private static final String CABECALHO =
            "Stone ID;Data da venda;Valor bruto;Valor líquido;Produto;Bandeira;Parcelas;NSU;"
                    + "Código de autorização;Status\n";

    private static List<LinhaDoExtrato> ler(String linhas) {
        return ler(linhas, FormatoDeNumero.BRASILEIRO);
    }

    private static List<LinhaDoExtrato> ler(String linhas, FormatoDeNumero formato) {
        var tabela = LeitorDeCsv.ler((CABECALHO + linhas).getBytes(StandardCharsets.UTF_8));
        var sugerido = MapeamentoDoExtrato.sugerir(tabela.cabecalho(), Optional.empty(), formato);
        return InterpretadorDoExtrato.interpretar(tabela, sugerido);
    }

    private static LinhaDoExtrato uma(String linha) {
        return ler(linha + "\n").getFirst();
    }

    @Test
    @DisplayName("a venda completa, com a hora do salão, crédito parcelado e a bandeira em maiúsculas")
    void venda_completa() {
        var linha = uma("ST123;18/09/2026 14:32;1.234,56;1.190,11;Crédito;visa;3x;654321;A1B2C3;Aprovada");

        assertThat(linha).isInstanceOfSatisfying(LinhaDoExtrato.Lida.class, lida -> {
            var venda = lida.transacao();
            assertThat(lida.linha()).isEqualTo(2);
            assertThat(venda.idNaAdquirente()).isEqualTo("ST123");
            assertThat(venda.capturadaEm()).isEqualTo(LocalDateTime.of(2026, 9, 18, 14, 32));
            assertThat(venda.horaInformada()).isTrue();
            assertThat(venda.valorBruto()).isEqualByComparingTo("1234.56");
            assertThat(venda.valorLiquido()).isEqualByComparingTo("1190.11");
            assertThat(venda.produto()).isEqualTo(ProdutoDoCartao.CREDITO);
            assertThat(venda.bandeira()).isEqualTo("VISA");
            assertThat(venda.parcelas()).isEqualTo(3);
            assertThat(venda.nsu()).isEqualTo("654321");
            assertThat(venda.autorizacao()).isEqualTo("A1B2C3");
            assertThat(venda.situacao()).isEqualTo(SituacaoDaTransacao.APROVADA);
        });
    }

    @Test
    @DisplayName("data em modo estrito: 31/02 é recusada, e não vira 3 de março")
    void data_estrita() {
        assertThat(uma("ST1;31/02/2026 10:00;10,00;;Débito;Elo;;;;Aprovada"))
                .isInstanceOfSatisfying(LinhaDoExtrato.Recusada.class, r ->
                        assertThat(r.motivo()).contains("data não reconhecida").contains("31/02/2026"));
    }

    @Test
    @DisplayName("só a data: meia-noite, e marcada como sem hora")
    void so_data() {
        assertThat(uma("ST1;18/09/2026;10,00;;Débito;Elo;;;;Aprovada"))
                .isInstanceOfSatisfying(LinhaDoExtrato.Lida.class, l ->
                        assertThat(l.transacao().horaInformada()).isFalse());
    }

    @Test
    @DisplayName("situação: negativas antes — \"não autorizada\" não vira aprovada")
    void situacoes() {
        assertThat(uma("ST1;18/09/2026 10:00;10,00;;Débito;Elo;;;;Cancelada"))
                .isInstanceOfSatisfying(LinhaDoExtrato.Lida.class, l ->
                        assertThat(l.transacao().situacao()).isEqualTo(SituacaoDaTransacao.CANCELADA));
        assertThat(uma("ST1;18/09/2026 10:00;10,00;;Débito;Elo;;;;Não autorizada"))
                .isInstanceOf(LinhaDoExtrato.Ignorada.class);
        assertThat(uma("ST1;18/09/2026 10:00;10,00;;Débito;Elo;;;;Negada"))
                .isInstanceOf(LinhaDoExtrato.Ignorada.class);
        assertThat(uma("ST1;18/09/2026 10:00;10,00;;Crédito;Elo;;;;Pré-autorizada"))
                .isInstanceOf(LinhaDoExtrato.Ignorada.class);
        assertThat(uma("ST1;18/09/2026 10:00;10,00;;Débito;Elo;;;;Em análise"))
                .isInstanceOfSatisfying(LinhaDoExtrato.Recusada.class, r ->
                        assertThat(r.motivo()).contains("situação não reconhecida"));
    }

    @Test
    @DisplayName("Pix é ignorado: não passa pela conciliação")
    void pix() {
        assertThat(uma("ST1;18/09/2026 10:00;10,00;;Pix;;;;;Aprovada"))
                .isInstanceOfSatisfying(LinhaDoExtrato.Ignorada.class, i ->
                        assertThat(i.motivo()).contains("Pix"));
    }

    @Test
    @DisplayName("valor bruto zero ou negativo é recusado: cancelamento vem pela situação")
    void valor_nao_positivo() {
        assertThat(uma("ST1;18/09/2026 10:00;-10,00;;Débito;Elo;;;;Aprovada"))
                .isInstanceOf(LinhaDoExtrato.Recusada.class);
        assertThat(uma("ST1;18/09/2026 10:00;0,00;;Débito;Elo;;;;Aprovada"))
                .isInstanceOf(LinhaDoExtrato.Recusada.class);
    }

    @Test
    @DisplayName("parcelas: \"3x\", \"À vista\" e vazio; o resto é recusado")
    void parcelas() {
        assertThat(InterpretadorDoExtrato.parcelas("3x")).isEqualTo(3);
        assertThat(InterpretadorDoExtrato.parcelas("12 parcelas")).isEqualTo(12);
        assertThat(InterpretadorDoExtrato.parcelas("À vista")).isEqualTo(1);
        assertThat(InterpretadorDoExtrato.parcelas(null)).isEqualTo(1);
        assertThat(InterpretadorDoExtrato.parcelas("muitas")).isNull();
        assertThat(uma("ST1;18/09/2026 10:00;10,00;;Crédito;Elo;muitas;;;Aprovada"))
                .isInstanceOf(LinhaDoExtrato.Recusada.class);
    }

    @Test
    @DisplayName("linha com colunas a mais é recusada dizendo quantas")
    void colunas_a_mais() {
        assertThat(uma("ST1;18/09/2026 10:00;10,00;;Débito;Elo;;;;Aprovada;sobrando"))
                .isInstanceOfSatisfying(LinhaDoExtrato.Recusada.class, r ->
                        assertThat(r.motivo()).contains("11 colunas").contains("10"));
    }

    @Test
    @DisplayName("formato de número trocado recusa a linha — em vez de gravar mil vezes o valor")
    void formato_trocado() {
        var linhas = ler("ST1;18/09/2026 10:00;1.234,56;;Débito;Elo;;;;Aprovada\n",
                FormatoDeNumero.INTERNACIONAL);

        assertThat(linhas).singleElement().isInstanceOfSatisfying(LinhaDoExtrato.Recusada.class,
                r -> assertThat(r.motivo()).contains("1.234,56"));
    }

    @Test
    @DisplayName("sem Stone ID não há o que conciliar")
    void sem_id() {
        assertThat(uma(";18/09/2026 10:00;10,00;;Débito;Elo;;;;Aprovada"))
                .isInstanceOfSatisfying(LinhaDoExtrato.Recusada.class, r ->
                        assertThat(r.motivo()).isEqualTo("sem Stone ID"));
    }
}
