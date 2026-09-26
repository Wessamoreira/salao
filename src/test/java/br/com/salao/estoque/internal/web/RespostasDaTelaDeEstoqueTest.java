package br.com.salao.estoque.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.estoque.api.AlertasDeEstoque;
import br.com.salao.estoque.api.InventarioDeEstoque;
import br.com.salao.estoque.api.LoteResumo;
import br.com.salao.estoque.api.SaldoDoProduto;
import br.com.salao.estoque.api.StatusDoInventario;
import br.com.salao.estoque.api.UnidadeDeMedida;
import br.com.salao.shared.dinheiro.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RT-EST-013 — o que a tela recebe.
 *
 * <p>Estes DTOs existem por duas razões que não aparecem em teste de caso de uso: {@code Money}
 * serializa como {@code {"valor": …}}, e método derivado de record não vira campo no JSON. Sem
 * eles a tela receberia dinheiro em duas formas e não receberia nenhuma das contas prontas — e
 * recalcular no JavaScript é a segunda cópia que ninguém corrige.
 */
class RespostasDaTelaDeEstoqueTest {

    @Test
    @DisplayName("saldo: dinheiro sai como número, e sem estoque o custo médio é nulo")
    void saldo() {
        var comEstoque = new SaldoDoProduto(UUID.randomUUID(), "Shampoo", UnidadeDeMedida.UN,
                new BigDecimal("12"), Money.de("20.50"), Money.de("246"));

        var resposta = ProdutosController.SaldoResponse.de(comEstoque);

        assertThat(resposta.custoMedio()).isEqualByComparingTo("20.50");
        assertThat(resposta.valorEmEstoque()).isEqualByComparingTo("246");
        assertThat(resposta.semEstoque()).isFalse();
    }

    @Test
    @DisplayName("custo médio nulo continua nulo: zero diria 'não custou nada'")
    void saldo_sem_estoque() {
        var vazio = new SaldoDoProduto(UUID.randomUUID(), "Tinta", UnidadeDeMedida.ML,
                BigDecimal.ZERO, null, Money.ZERO);

        var resposta = ProdutosController.SaldoResponse.de(vazio);

        assertThat(resposta.custoMedio()).isNull();
        assertThat(resposta.semEstoque()).isTrue();
    }

    @Test
    @DisplayName("lote: o custo unitário sai como número")
    void lote() {
        var lote = new LoteResumo(UUID.randomUUID(), UUID.randomUUID(), "NF-4421",
                Money.de("19.90"), new BigDecimal("24"), LocalDate.of(2027, 3, 1),
                LocalDate.of(2026, 9, 1));

        assertThat(ProdutosController.LoteResponse.de(lote).custoUnitario())
                .isEqualByComparingTo("19.90");
    }

    @Test
    @DisplayName("alerta: o quanto falta e o custo em risco chegam prontos")
    void alertas() {
        // `faltando` é o número do pedido de compra e `custoEmRisco` é o que faz o dono agir.
        // Os dois são derivados, e sem DTO simplesmente não chegavam à tela.
        var alertas = new AlertasDeEstoque(30,
                List.of(new AlertasDeEstoque.ProdutoEmFalta(UUID.randomUUID(), "Tinta",
                        UnidadeDeMedida.ML, new BigDecimal("120"), new BigDecimal("500"),
                        Money.de("60"))),
                List.of(new AlertasDeEstoque.LoteVencendo(UUID.randomUUID(), UUID.randomUUID(),
                        "Shampoo", UnidadeDeMedida.UN, "NF-4421", new BigDecimal("9"),
                        LocalDate.of(2026, 10, 10), 14, Money.de("180"))));

        var resposta = ProdutosController.AlertasResponse.de(alertas);

        assertThat(resposta.abaixoDoMinimo().getFirst().faltando()).isEqualByComparingTo("380");
        assertThat(resposta.abaixoDoMinimo().getFirst().zerado()).isFalse();
        assertThat(resposta.vencendo().getFirst().custoEmRisco()).isEqualByComparingTo("180");
        assertThat(resposta.vencendo().getFirst().vencido()).isFalse();
        assertThat(resposta.custoEmRisco()).isEqualByComparingTo("180");
    }

    @Test
    @DisplayName("contagem: a divergência e o saldo vêm calculados, não para a tela refazer")
    void inventario() {
        var contagem = new InventarioDeEstoque(UUID.randomUUID(), StatusDoInventario.ABERTO,
                "contagem do mês", Instant.EPOCH, null,
                List.of(item("NF-1", "12", "9"), item("NF-2", "30", "30")));

        var resposta = InventariosController.InventarioResponse.de(contagem);

        assertThat(resposta.quantasDivergem()).isEqualTo(1);
        assertThat(resposta.saldoDasDivergencias()).isEqualByComparingTo("-3");
        assertThat(resposta.itens().getFirst().divergencia()).isEqualByComparingTo("-3");
        assertThat(resposta.itens().getFirst().bateu()).isFalse();
        assertThat(resposta.itens().getLast().bateu()).isTrue();
    }

    private InventarioDeEstoque.ItemContado item(String lote, String esperada, String contada) {
        return new InventarioDeEstoque.ItemContado(UUID.randomUUID(), UUID.randomUUID(),
                "Shampoo", lote, new BigDecimal(esperada), new BigDecimal(contada), null,
                Instant.EPOCH);
    }
}
