package br.com.salao.financeiro.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.financeiro.api.FormaDePagamento;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RT-FIN-011 — o que "usar a versão da Stone" muda, sem banco (RN-FIN-034). */
class CorrecaoTest {

    private static Correcao calcular(Set<Divergencia> divergencias, ProdutoDoCartao produto,
                                     String bandeira, int parcelas, String venda, String pagamento) {
        return Correcao.calcular(divergencias, produto, bandeira, parcelas, new BigDecimal(venda),
                new BigDecimal(pagamento));
    }

    @Test
    @DisplayName("forma trocada: o pagamento passa a ser o que a maquininha diz")
    void forma() {
        var correcao = calcular(Set.of(Divergencia.FORMA), ProdutoDoCartao.CREDITO, null, 1,
                "80.00", "80.00");

        assertThat(correcao.forma()).isEqualTo(FormaDePagamento.CREDITO);
        assertThat(correcao.diferenca()).isNull();
        assertThat(correcao.restantes()).isEmpty();
        assertThat(correcao.mudaAlgo()).isTrue();
    }

    @Test
    @DisplayName("a Stone cobrou mais do que o sistema registrou: a diferença é positiva")
    void valor_a_maior() {
        var correcao = calcular(Set.of(Divergencia.VALOR), ProdutoDoCartao.CREDITO, null, 1,
                "90.00", "80.00");

        assertThat(correcao.diferenca()).isEqualByComparingTo("10.00");
        assertThat(correcao.forma()).isNull();
    }

    @Test
    @DisplayName("a Stone cobrou menos: a diferença sai negativa, e o caixa desce")
    void valor_a_menor() {
        var correcao = calcular(Set.of(Divergencia.VALOR), ProdutoDoCartao.DEBITO, null, 1,
                "70.00", "80.00");

        assertThat(correcao.diferenca()).isEqualByComparingTo("-10.00");
    }

    @Test
    @DisplayName("bandeira e parcelas vêm da venda")
    void bandeira_e_parcelas() {
        var correcao = calcular(Set.of(Divergencia.BANDEIRA, Divergencia.PARCELAS),
                ProdutoDoCartao.CREDITO, "VISA", 3, "80.00", "80.00");

        assertThat(correcao.bandeira()).isEqualTo("VISA");
        assertThat(correcao.parcelas()).isEqualTo(3);
        assertThat(correcao.forma()).isNull();
    }

    @Test
    @DisplayName("venda cancelada não se corrige: não há versão certa para copiar")
    void cancelada_sozinha() {
        var correcao = calcular(Set.of(Divergencia.CANCELADA_NA_ADQUIRENTE),
                ProdutoDoCartao.CREDITO, null, 1, "80.00", "80.00");

        assertThat(correcao.mudaAlgo()).isFalse();
        assertThat(correcao.restantes()).containsExactly(Divergencia.CANCELADA_NA_ADQUIRENTE);
    }

    @Test
    @DisplayName("forma e cancelada: corrige a forma, e o par segue divergente pelo cancelamento")
    void corrige_o_que_da_e_o_resto_fica() {
        var correcao = calcular(Set.of(Divergencia.FORMA, Divergencia.CANCELADA_NA_ADQUIRENTE),
                ProdutoDoCartao.DEBITO, null, 1, "80.00", "80.00");

        assertThat(correcao.forma()).isEqualTo(FormaDePagamento.DEBITO);
        assertThat(correcao.mudaAlgo()).isTrue();
        assertThat(correcao.restantes()).containsExactly(Divergencia.CANCELADA_NA_ADQUIRENTE);
    }

    @Test
    @DisplayName("venda sem produto conhecido não vira forma inventada")
    void produto_desconhecido_nao_inventa_forma() {
        for (ProdutoDoCartao produto : List.of(ProdutoDoCartao.OUTRO)) {
            var correcao = calcular(Set.of(Divergencia.FORMA), produto, null, 1, "80.00", "80.00");

            assertThat(correcao.forma()).isNull();
            assertThat(correcao.mudaAlgo()).isFalse();
            assertThat(correcao.restantes()).containsExactly(Divergencia.FORMA);
        }
        var semProduto = calcular(Set.of(Divergencia.FORMA), null, null, 1, "80.00", "80.00");
        assertThat(semProduto.forma()).isNull();
        assertThat(semProduto.mudaAlgo()).isFalse();
    }

    @Test
    @DisplayName("forma e valor juntos: as duas correções saem de uma vez")
    void tudo_de_uma_vez() {
        var correcao = calcular(Set.of(Divergencia.FORMA, Divergencia.VALOR),
                ProdutoDoCartao.CREDITO, null, 1, "90.00", "80.00");

        assertThat(correcao.forma()).isEqualTo(FormaDePagamento.CREDITO);
        assertThat(correcao.diferenca()).isEqualByComparingTo("10.00");
        assertThat(correcao.restantes()).isEmpty();
    }
}
