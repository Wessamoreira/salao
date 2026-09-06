package br.com.salao.estoque.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.estoque.api.FinalidadeDoProduto;
import br.com.salao.estoque.api.SaldoDoProduto;
import br.com.salao.estoque.api.TipoDeMovimento;
import br.com.salao.estoque.api.UnidadeDeMedida;
import br.com.salao.estoque.internal.infra.LotesJdbc;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-EST-003 — saldo e custo médio ponderado, como projeção. */
@WithMockUser(authorities = { Permissao.ESTOQUE_LER, Permissao.ESTOQUE_ESCREVER,
        Permissao.PRODUTO_PRECO_ESCREVER })
class SaldoEcustoMedioIT extends AbstractPostgresIT {

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarProdutoUseCase cadastrarProduto;
    @Autowired private RegistrarEntradaDeLoteUseCase entrada;
    @Autowired private ConsultarSaldoUseCase saldo;
    @Autowired private LotesJdbc lotes;
    @Autowired private Relogio relogio;

    private UUID tenant;
    private UUID shampoo;

    private void cenario() {
        tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        shampoo = produto("Shampoo");
    }

    private UUID produto(String nome) {
        return TenantContext.obter(tenant, () -> cadastrarProduto.executar(new DadosDoProduto(
                nome, null, FinalidadeDoProduto.VENDA, UnidadeDeMedida.UN,
                Money.de("45")))).id();
    }

    private UUID receber(UUID produtoId, String quantidade, String custo) {
        return TenantContext.obter(tenant, () -> entrada.executar(produtoId,
                new DadosDaEntrada(new BigDecimal(quantidade), Money.de(custo), null,
                        LocalDate.now().plusMonths(6), null))).id();
    }

    /** Uma saída avulsa, do jeito que RT-EST-004 vai gravar: quantidade negativa. */
    private void tirar(UUID produtoId, UUID loteId, String quantidade, String custo) {
        TenantContext.executar(tenant, () -> lotes.inserirMovimento(tenant, produtoId, loteId,
                TipoDeMovimento.VENDA, new BigDecimal(quantidade).negate(), Money.de(custo),
                null, null, null, relogio.agora(), null));
    }

    private SaldoDoProduto saldoDe(UUID produtoId) {
        return TenantContext.obter(tenant, () -> saldo.doProduto(produtoId)).orElseThrow();
    }

    // ------------------------------------------------------------------ a média

    @Test
    @DisplayName("o custo médio pondera pelos lotes que ainda têm unidades")
    void custo_medio_ponderado() {
        // 10 a R$ 20,00 e 5 a R$ 26,00 → (200 + 130) / 15 = R$ 22,00. É este número que
        // transforma "vendi por 45" em "ganhei 23".
        cenario();
        receber(shampoo, "10", "20");
        receber(shampoo, "5", "26");

        var atual = saldoDe(shampoo);

        assertThat(atual.quantidade()).isEqualByComparingTo("15");
        assertThat(atual.custoMedio()).isEqualTo(Money.de("22"));
        assertThat(atual.valorEmEstoque()).isEqualTo(Money.de("330"));
    }

    @Test
    @DisplayName("lote esvaziado sai da média: ela é do que ESTÁ na prateleira")
    void lote_zerado_nao_pondera() {
        // Incluí-lo puxaria a média para o custo de mercadoria que já saiu — e o valor em estoque
        // passaria a contar unidades que não existem.
        cenario();
        UUID barato = receber(shampoo, "10", "20");
        receber(shampoo, "5", "26");
        tirar(shampoo, barato, "10", "20");

        var atual = saldoDe(shampoo);

        assertThat(atual.quantidade()).isEqualByComparingTo("5");
        assertThat(atual.custoMedio()).isEqualTo(Money.de("26"));
        assertThat(atual.valorEmEstoque()).isEqualTo(Money.de("130"));
    }

    @Test
    @DisplayName("saída parcial mantém o lote na média, com o peso que sobrou")
    void saida_parcial() {
        cenario();
        UUID barato = receber(shampoo, "10", "20");
        receber(shampoo, "5", "26");
        tirar(shampoo, barato, "8", "20");

        var atual = saldoDe(shampoo);

        // Restam 2 a 20 e 5 a 26 → (40 + 130) / 7 = 24,2857...
        assertThat(atual.quantidade()).isEqualByComparingTo("7");
        assertThat(atual.custoMedio().valor()).isEqualByComparingTo("24.2857");
    }

    @Test
    @DisplayName("sem estoque o custo médio é NULO, e nulo não é zero")
    void sem_estoque_sem_media() {
        // Zero significaria "tenho unidades que não custaram nada" — que é o caso real da amostra
        // do fornecedor. Sem estoque não há média a tirar.
        cenario();

        var atual = saldoDe(shampoo);

        assertThat(atual.quantidade()).isEqualByComparingTo("0");
        assertThat(atual.custoMedio()).isNull();
        assertThat(atual.valorEmEstoque()).isEqualTo(Money.ZERO);
        assertThat(atual.semEstoque()).isTrue();
    }

    @Test
    @DisplayName("amostra de graça entra na média com custo zero, e isso não é o mesmo que nulo")
    void amostra_de_graca() {
        cenario();
        receber(shampoo, "10", "20");
        receber(shampoo, "10", "0");

        var atual = saldoDe(shampoo);

        assertThat(atual.custoMedio()).isEqualTo(Money.de("10"));
        assertThat(atual.custoMedio()).isNotNull();
    }

    // ------------------------------------------------------------------ projeção, não coluna

    @Test
    @DisplayName("o saldo negativo aparece; esconder seria esconder o erro")
    void saldo_negativo_aparece() {
        // Só acontece se um ajuste errar, e é exatamente por isso que precisa ficar visível
        // (RT-EST-005 é quem corrige). O lote negativo não pondera a média — peso negativo
        // subtrairia valor de um estoque que existe.
        cenario();
        UUID lote = receber(shampoo, "5", "20");
        tirar(shampoo, lote, "8", "20");

        var atual = saldoDe(shampoo);

        assertThat(atual.quantidade()).isEqualByComparingTo("-3");
        assertThat(atual.custoMedio()).as("não há lote com saldo positivo para ponderar").isNull();
        assertThat(atual.valorEmEstoque()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("a lista traz também o que está zerado — é ela que diz o que repor")
    void lista_de_reposicao() {
        cenario();
        receber(shampoo, "10", "20");
        UUID mascara = produto("Máscara");

        var todos = TenantContext.obter(tenant, () -> saldo.deTodos(false));
        var comEstoque = TenantContext.obter(tenant, () -> saldo.deTodos(true));

        assertThat(todos).extracting(SaldoDoProduto::nome)
                .containsExactly("Máscara", "Shampoo");
        assertThat(todos).filteredOn(SaldoDoProduto::semEstoque)
                .extracting(SaldoDoProduto::produtoId).containsExactly(mascara);
        assertThat(comEstoque).extracting(SaldoDoProduto::nome).containsExactly("Shampoo");
    }

    @Test
    @DisplayName("um salão não vê o saldo do outro")
    void isolamento_entre_saloes() {
        cenario();
        receber(shampoo, "10", "20");
        UUID produtoDoA = shampoo;
        UUID salaoB = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Outro " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        assertThat(TenantContext.obter(salaoB, () -> saldo.doProduto(produtoDoA))).isEmpty();
        assertThat(TenantContext.obter(salaoB, () -> saldo.deTodos(false))).isEmpty();
    }
}
