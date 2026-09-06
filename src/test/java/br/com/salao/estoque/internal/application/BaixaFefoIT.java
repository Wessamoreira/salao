package br.com.salao.estoque.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.estoque.api.BaixaDeEstoque;
import br.com.salao.estoque.api.EstoqueApi;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-EST-004 — sai primeiro o que vence primeiro. */
@WithMockUser(authorities = { Permissao.ESTOQUE_LER, Permissao.ESTOQUE_ESCREVER,
        Permissao.PRODUTO_PRECO_ESCREVER })
class BaixaFefoIT extends AbstractPostgresIT {

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarProdutoUseCase cadastrarProduto;
    @Autowired private RegistrarEntradaDeLoteUseCase entrada;
    @Autowired private ConsultarSaldoUseCase saldo;
    @Autowired private ListarLotesUseCase listarLotes;
    @Autowired private EstoqueApi estoque;

    private UUID tenant;
    private UUID shampoo;

    private void cenario() {
        tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        shampoo = TenantContext.obter(tenant, () -> cadastrarProduto.executar(new DadosDoProduto(
                "Shampoo", null, FinalidadeDoProduto.VENDA, UnidadeDeMedida.UN,
                Money.de("45")))).id();
    }

    private UUID receber(String quantidade, String custo, LocalDate validade) {
        return TenantContext.obter(tenant, () -> entrada.executar(shampoo, new DadosDaEntrada(
                new BigDecimal(quantidade), Money.de(custo), null, validade, null))).id();
    }

    private BaixaDeEstoque baixar(String quantidade) {
        return TenantContext.obter(tenant, () -> estoque.baixar(shampoo,
                new BigDecimal(quantidade), TipoDeMovimento.VENDA, "ITEM_COMANDA",
                UUID.randomUUID()));
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ a ordem

    @Test
    @DisplayName("sai o que vence primeiro, não o que chegou primeiro")
    void fefo_e_nao_fifo() {
        // Uma caixa comprada ontem com validade curta precisa sair antes de uma comprada no mês
        // passado com validade longa. FIFO deixaria a primeira vencer no armário.
        cenario();
        UUID antigoValidadeLonga = receber("5", "20", LocalDate.now().plusMonths(12));
        UUID novoValidadeCurta = receber("5", "30", LocalDate.now().plusMonths(1));

        var baixa = baixar("3");

        assertThat(baixa.parcelas()).hasSize(1);
        assertThat(baixa.parcelas().get(0).loteId()).isEqualTo(novoValidadeCurta);
        assertThat(baixa.custoMedioUnitario()).isEqualTo(Money.de("30"));
        assertThat(TenantContext.obter(tenant, () -> listarLotes.executar(shampoo, false)))
                .filteredOn(l -> l.id().equals(antigoValidadeLonga))
                .allSatisfy(l -> assertThat(l.quantidade()).isEqualByComparingTo("5"));
    }

    @Test
    @DisplayName("o que não vence sai por último")
    void sem_validade_por_ultimo() {
        cenario();
        receber("5", "20", null);
        UUID comValidade = receber("5", "30", LocalDate.now().plusMonths(12));

        var baixa = baixar("2");

        assertThat(baixa.parcelas().get(0).loteId()).isEqualTo(comValidade);
    }

    // ------------------------------------------------------------------ vários lotes

    @Test
    @DisplayName("a baixa atravessa lotes, e o custo é a média ponderada DO QUE SAIU")
    void baixa_em_varios_lotes() {
        // 2 a R$ 20,00 e 1 a R$ 30,00 → (40 + 30) / 3 = R$ 23,3333. Não é o custo médio do
        // produto (RT-EST-003), que ainda inclui o que ficou na prateleira.
        cenario();
        UUID primeiro = receber("2", "20", LocalDate.now().plusMonths(1));
        UUID segundo = receber("5", "30", LocalDate.now().plusMonths(6));

        var baixa = baixar("3");

        assertThat(baixa.parcelas()).hasSize(2);
        assertThat(baixa.parcelas()).extracting(BaixaDeEstoque.ParcelaDaBaixa::loteId)
                .containsExactly(primeiro, segundo);
        assertThat(baixa.parcelas()).extracting(p -> p.quantidade().stripTrailingZeros()
                .toPlainString()).containsExactly("2", "1");
        assertThat(baixa.custoTotal()).isEqualTo(Money.de("70"));
        assertThat(baixa.custoMedioUnitario().valor()).isEqualByComparingTo("23.3333");
    }

    @Test
    @DisplayName("o custo da retirada é diferente do custo médio do produto, e é de propósito")
    void custo_da_retirada_nao_e_o_do_produto() {
        cenario();
        receber("2", "20", LocalDate.now().plusMonths(1));
        receber("8", "30", LocalDate.now().plusMonths(6));

        var baixa = baixar("2");
        var atual = TenantContext.obter(tenant, () -> saldo.doProduto(shampoo)).orElseThrow();

        assertThat(baixa.custoMedioUnitario())
                .as("saiu tudo do lote de 20").isEqualTo(Money.de("20"));
        assertThat(atual.custoMedio())
                .as("o que ficou na prateleira é todo de 30").isEqualTo(Money.de("30"));
    }

    @Test
    @DisplayName("a baixa some do saldo, e o lote esvaziado sai da média")
    void saldo_acompanha() {
        cenario();
        receber("2", "20", LocalDate.now().plusMonths(1));
        receber("5", "30", LocalDate.now().plusMonths(6));

        baixar("2");
        var atual = TenantContext.obter(tenant, () -> saldo.doProduto(shampoo)).orElseThrow();

        assertThat(atual.quantidade()).isEqualByComparingTo("5");
        assertThat(atual.custoMedio()).isEqualTo(Money.de("30"));
    }

    @Test
    @DisplayName("lote vazio é pulado sem estourar")
    void lote_vazio_e_pulado() {
        cenario();
        receber("2", "20", LocalDate.now().plusMonths(1));
        receber("5", "30", LocalDate.now().plusMonths(6));
        baixar("2");

        var segunda = baixar("3");

        assertThat(segunda.parcelas()).hasSize(1);
        assertThat(segunda.custoMedioUnitario()).isEqualTo(Money.de("30"));
    }

    @Test
    @DisplayName("consumo interno sai pelo mesmo caminho, com outro rótulo")
    void consumo_interno() {
        cenario();
        receber("10", "20", LocalDate.now().plusMonths(6));

        TenantContext.executar(tenant, () -> estoque.baixar(shampoo, new BigDecimal("4"),
                TipoDeMovimento.CONSUMO_INTERNO, "SERVICO", UUID.randomUUID()));

        assertThat(TenantContext.obter(tenant, () -> saldo.doProduto(shampoo))
                .orElseThrow().quantidade()).isEqualByComparingTo("6");
    }

    // ------------------------------------------------------------------ o que é recusado

    @Test
    @DisplayName("saldo insuficiente diz quanto HÁ, não só que faltou")
    void saldo_insuficiente() {
        // Quem está no balcão precisa saber se vende o que resta ou se troca o produto, e
        // "saldo insuficiente" sozinho não responde nenhuma das duas.
        cenario();
        receber("3", "20", LocalDate.now().plusMonths(1));

        Throwable erro = catchThrowable(() -> baixar("5"));

        assertThat(codigoDe(erro)).isEqualTo("ER-EST-SALDO_INSUFICIENTE");
        assertThat(erro).hasMessageContaining("há 3");
    }

    @Test
    @DisplayName("nada foi baixado quando a saída é recusada")
    void recusa_nao_deixa_rastro() {
        cenario();
        receber("3", "20", LocalDate.now().plusMonths(1));

        catchThrowable(() -> baixar("5"));

        assertThat(TenantContext.obter(tenant, () -> saldo.doProduto(shampoo))
                .orElseThrow().quantidade()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("quantidade zero ou negativa não baixa")
    void quantidade_invalida() {
        cenario();
        receber("3", "20", null);

        assertThat(codigoDe(catchThrowable(() -> baixar("0"))))
                .isEqualTo("ER-EST-QUANTIDADE_INVALIDA");
        assertThat(codigoDe(catchThrowable(() -> baixar("-1"))))
                .isEqualTo("ER-EST-QUANTIDADE_INVALIDA");
    }

    @Test
    @DisplayName("produto sem estoque nenhum")
    void sem_estoque() {
        cenario();

        assertThat(codigoDe(catchThrowable(() -> baixar("1"))))
                .isEqualTo("ER-EST-SALDO_INSUFICIENTE");
    }

    // ------------------------------------------------------------------ concorrência

    @Test
    @DisplayName("duas baixas simultâneas não levam a mesma última unidade")
    void concorrencia_na_ultima_unidade() throws Exception {
        // Sem `for update`, as duas leem o mesmo saldo, as duas decidem que cabe, e o lote fica
        // negativo — o saldo ser projeção resolve saldo errado, não corrida.
        cenario();
        receber("1", "20", LocalDate.now().plusMonths(1));

        Callable<Object> tentativa = () -> {
            try {
                return TenantContext.obter(tenant, () -> estoque.baixar(shampoo, BigDecimal.ONE,
                        TipoDeMovimento.VENDA, "ITEM_COMANDA", UUID.randomUUID()));
            } catch (RuntimeException e) {
                return e;
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> resultados = pool.invokeAll(List.of(tentativa, tentativa));
            long sucessos = resultados.stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    return e;
                }
            }).filter(r -> r instanceof BaixaDeEstoque).count();

            assertThat(sucessos).as("uma leva a unidade; a outra não").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(TenantContext.obter(tenant, () -> saldo.doProduto(shampoo))
                .orElseThrow().quantidade())
                .as("o saldo nunca fica negativo").isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("um salão não baixa o estoque do outro")
    void isolamento_entre_saloes() {
        cenario();
        receber("10", "20", null);
        UUID produtoDoA = shampoo;
        UUID salaoB = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Outro " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        Throwable erro = catchThrowable(() -> TenantContext.executar(salaoB,
                () -> estoque.baixar(produtoDoA, BigDecimal.ONE, TipoDeMovimento.VENDA,
                        "ITEM_COMANDA", UUID.randomUUID())));

        assertThat(codigoDe(erro)).isEqualTo("ER-EST-SALDO_INSUFICIENTE");
    }
}
