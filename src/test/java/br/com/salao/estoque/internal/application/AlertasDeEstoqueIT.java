package br.com.salao.estoque.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.estoque.api.AlertasDeEstoque;
import br.com.salao.estoque.api.FinalidadeDoProduto;
import br.com.salao.estoque.api.TipoDeMovimento;
import br.com.salao.estoque.api.UnidadeDeMedida;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-EST-008 — alertas de estoque mínimo e de lote vencendo. */
@WithMockUser(authorities = { Permissao.ESTOQUE_LER, Permissao.ESTOQUE_ESCREVER,
        Permissao.PRODUTO_PRECO_ESCREVER, Permissao.USUARIO_GERENCIAR })
class AlertasDeEstoqueIT extends AbstractPostgresIT {

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarProdutoUseCase cadastrarProduto;
    @Autowired private RegistrarEntradaDeLoteUseCase entrada;
    @Autowired private DefinirEstoqueMinimoUseCase definirMinimo;
    @Autowired private ConsultarAlertasUseCase alertas;
    @Autowired private br.com.salao.estoque.api.EstoqueApi estoque;

    private UUID tenant;

    private void cenario() {
        tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
    }

    private UUID produto(String nome) {
        return TenantContext.obter(tenant, () -> cadastrarProduto.executar(new DadosDoProduto(
                nome, null, FinalidadeDoProduto.VENDA, UnidadeDeMedida.UN,
                Money.de("45")))).id();
    }

    private void receber(UUID produtoId, String quantidade, String custo, LocalDate validade) {
        TenantContext.executar(tenant, () -> entrada.executar(produtoId, new DadosDaEntrada(
                new BigDecimal(quantidade), Money.de(custo), null, validade, null)));
    }

    private AlertasDeEstoque ver() {
        return TenantContext.obter(tenant, () -> alertas.executar(null));
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ mínimo

    @Test
    @DisplayName("produto SEM mínimo definido não alerta, mesmo zerado")
    void sem_minimo_nao_alerta() {
        // Nulo não é zero. Se a coluna tivesse default zero, todo produto nasceria alertando ao
        // zerar — e uma lista de cem linhas que ninguém lê ocupa o lugar do que importa.
        cenario();
        produto("Shampoo sem mínimo");

        assertThat(ver().abaixoDoMinimo()).isEmpty();
    }

    @Test
    @DisplayName("mínimo ZERO significa 'avise quando acabar', e é uma decisão")
    void minimo_zero_alerta_ao_zerar() {
        cenario();
        UUID shampoo = produto("Shampoo de vitrine");
        TenantContext.executar(tenant, () -> definirMinimo.executar(shampoo, BigDecimal.ZERO));

        var visto = ver();

        assertThat(visto.abaixoDoMinimo()).singleElement().satisfies(falta -> {
            assertThat(falta.nome()).isEqualTo("Shampoo de vitrine");
            assertThat(falta.zerado()).isTrue();
            assertThat(falta.faltando()).isEqualByComparingTo("0");
        });
    }

    @Test
    @DisplayName("abaixo do mínimo diz quanto falta — é o número do pedido de compra")
    void quanto_falta() {
        cenario();
        UUID shampoo = produto("Shampoo");
        receber(shampoo, "4", "20", null);
        TenantContext.executar(tenant,
                () -> definirMinimo.executar(shampoo, new BigDecimal("10")));

        assertThat(ver().abaixoDoMinimo()).singleElement().satisfies(falta -> {
            assertThat(falta.quantidade()).isEqualByComparingTo("4");
            assertThat(falta.minimo()).isEqualByComparingTo("10");
            assertThat(falta.faltando()).isEqualByComparingTo("6");
            assertThat(falta.zerado()).isFalse();
        });
    }

    @Test
    @DisplayName("acima do mínimo não alerta; exatamente no mínimo, sim")
    void limite_do_minimo() {
        // No mínimo já é hora de comprar: o mínimo é o piso, não o alarme de incêndio.
        cenario();
        UUID folgado = produto("Folgado");
        UUID justo = produto("Justo");
        receber(folgado, "11", "20", null);
        receber(justo, "10", "20", null);
        TenantContext.executar(tenant, () -> definirMinimo.executar(folgado, new BigDecimal("10")));
        TenantContext.executar(tenant, () -> definirMinimo.executar(justo, new BigDecimal("10")));

        assertThat(ver().abaixoDoMinimo()).extracting(AlertasDeEstoque.ProdutoEmFalta::nome)
                .containsExactly("Justo");
    }

    @Test
    @DisplayName("remover o mínimo tira o produto da lista")
    void remover_o_minimo() {
        cenario();
        UUID shampoo = produto("Shampoo");
        TenantContext.executar(tenant, () -> definirMinimo.executar(shampoo, new BigDecimal("5")));
        assertThat(ver().abaixoDoMinimo()).hasSize(1);

        TenantContext.executar(tenant, () -> definirMinimo.executar(shampoo, null));

        assertThat(ver().abaixoDoMinimo()).isEmpty();
    }

    @Test
    @DisplayName("mínimo negativo é recusado")
    void minimo_negativo() {
        cenario();
        UUID shampoo = produto("Shampoo");

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> definirMinimo.executar(shampoo, new BigDecimal("-1"))));

        assertThat(codigoDe(erro)).isEqualTo("ER-EST-QUANTIDADE_INVALIDA");
    }

    // ------------------------------------------------------------------ validade

    @Test
    @DisplayName("lote vencendo dentro da janela aparece, com o custo em risco")
    void lote_vencendo() {
        // O custo em risco é o número que faz o dono agir: "R$ 200 vão virar lixo em 10 dias".
        cenario();
        UUID tinta = produto("Tinta");
        receber(tinta, "10", "20", LocalDate.now().plusDays(10));

        var visto = ver();

        assertThat(visto.vencendo()).singleElement().satisfies(lote -> {
            assertThat(lote.produto()).isEqualTo("Tinta");
            assertThat(lote.diasRestantes()).isEqualTo(10);
            assertThat(lote.vencido()).isFalse();
            assertThat(lote.custoEmRisco()).isEqualTo(Money.de("200"));
        });
        assertThat(visto.custoEmRisco()).isEqualTo(Money.de("200"));
    }

    @Test
    @DisplayName("lote já vencido entra na MESMA lista: a ação é a mesma")
    void lote_vencido() {
        cenario();
        UUID tinta = produto("Tinta");
        receber(tinta, "5", "20", LocalDate.now().minusDays(3));

        assertThat(ver().vencendo()).singleElement().satisfies(lote -> {
            assertThat(lote.vencido()).isTrue();
            assertThat(lote.diasRestantes()).isEqualTo(-3);
        });
    }

    @Test
    @DisplayName("fora da janela não aparece")
    void fora_da_janela() {
        cenario();
        UUID tinta = produto("Tinta");
        receber(tinta, "5", "20", LocalDate.now().plusDays(60));

        assertThat(ver().vencendo()).isEmpty();
        // Com a janela alargada, aparece.
        assertThat(TenantContext.obter(tenant, () -> alertas.executar(90)).vencendo()).hasSize(1);
    }

    @Test
    @DisplayName("lote sem validade nunca vence")
    void sem_validade() {
        cenario();
        UUID shampoo = produto("Shampoo");
        receber(shampoo, "5", "20", null);

        assertThat(TenantContext.obter(tenant, () -> alertas.executar(3650)).vencendo()).isEmpty();
    }

    @Test
    @DisplayName("lote esgotado que vai vencer não é problema de ninguém")
    void lote_esgotado_nao_alerta() {
        cenario();
        UUID tinta = produto("Tinta");
        receber(tinta, "5", "20", LocalDate.now().plusDays(5));
        TenantContext.executar(tenant, () -> estoque.baixar(tinta, new BigDecimal("5"),
                TipoDeMovimento.VENDA, "ITEM_COMANDA", UUID.randomUUID()));

        assertThat(ver().vencendo()).isEmpty();
    }

    // ------------------------------------------------------------------ a tela

    @Test
    @DisplayName("sem nada a avisar, a resposta é vazia e sabe disso")
    void nada_a_avisar() {
        cenario();
        produto("Shampoo");

        var visto = ver();

        assertThat(visto.vazio()).isTrue();
        assertThat(visto.diasDeAviso()).isEqualTo(ConsultarAlertasUseCase.DIAS_DE_AVISO_PADRAO);
    }

    @Test
    @DisplayName("um salão não vê o alerta do outro")
    void isolamento_entre_saloes() {
        cenario();
        UUID tinta = produto("Tinta");
        receber(tinta, "5", "20", LocalDate.now().plusDays(5));
        TenantContext.executar(tenant, () -> definirMinimo.executar(tinta, new BigDecimal("99")));
        UUID salaoB = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Outro " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        assertThat(TenantContext.obter(salaoB, () -> alertas.executar(null)).vazio()).isTrue();
        assertThat(ver().vazio()).isFalse();
    }

    @Test
    @DisplayName("sem estoque:read, não vê alertas")
    @WithMockUser(authorities = Permissao.CLIENTE_LER)
    void sem_permissao() {
        UUID salao = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        assertThatThrownBy(() -> TenantContext.executar(salao, () -> alertas.executar(null)))
                .isInstanceOf(AccessDeniedException.class);
    }
}
