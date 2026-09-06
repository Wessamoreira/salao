package br.com.salao.financeiro.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.atendimento.internal.application.AbrirComandaUseCase;
import br.com.salao.atendimento.internal.application.AdicionarItemDeProdutoUseCase;
import br.com.salao.atendimento.internal.application.ConsultarContaUseCase;
import br.com.salao.atendimento.internal.application.RegistrarPagamentoUseCase;
import br.com.salao.atendimento.internal.application.RemoverPagamentoUseCase;
import br.com.salao.cliente.internal.application.CadastrarClienteUseCase;
import br.com.salao.cliente.internal.application.DadosDoCliente;
import br.com.salao.estoque.api.FinalidadeDoProduto;
import br.com.salao.estoque.api.UnidadeDeMedida;
import br.com.salao.estoque.internal.application.CadastrarProdutoUseCase;
import br.com.salao.estoque.internal.application.DadosDoProduto;
import br.com.salao.financeiro.api.DadosDoPagamento;
import br.com.salao.financeiro.api.FormaDePagamento;
import br.com.salao.financeiro.api.PagamentoApi;
import br.com.salao.financeiro.api.StatusDeConciliacao;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-FIN-001 — receber pela comanda, com split. */
@WithMockUser(authorities = { Permissao.COMANDA_ABRIR, Permissao.COMANDA_FECHAR,
        Permissao.CLIENTE_ESCREVER, Permissao.ESTOQUE_ESCREVER,
        Permissao.PRODUTO_PRECO_ESCREVER })
class RegistrarPagamentoIT extends AbstractPostgresIT {

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarClienteUseCase cadastrarCliente;
    @Autowired private CadastrarProdutoUseCase cadastrarProduto;
    @Autowired private AbrirComandaUseCase abrir;
    @Autowired private AdicionarItemDeProdutoUseCase adicionarProduto;
    @Autowired private RegistrarPagamentoUseCase registrar;
    @Autowired private RemoverPagamentoUseCase remover;
    @Autowired private ConsultarContaUseCase consultar;
    @Autowired private PagamentoApi pagamentos;

    private UUID tenant;
    private UUID comanda;

    /** Comanda com um produto de R$ 100,00 — conta redonda para o split ficar legível. */
    private void cenario() {
        tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        UUID maria = TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                new DadosDoCliente("Maria Silva", null, null, null, null)));
        UUID shampoo = TenantContext.obter(tenant, () -> cadastrarProduto.executar(
                new DadosDoProduto("Shampoo", null, FinalidadeDoProduto.VENDA,
                        UnidadeDeMedida.UN, Money.de("100")))).id();
        comanda = TenantContext.obter(tenant, () -> abrir.avulsa(maria)).comanda().id();
        TenantContext.executar(tenant, () -> adicionarProduto.executar(comanda, shampoo, null));
    }

    private void pagar(FormaDePagamento forma, String valor) {
        TenantContext.executar(tenant, () -> registrar.executar(comanda,
                DadosDoPagamento.de(forma, Money.de(valor))));
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ split

    @Test
    @DisplayName("metade no cartão e metade no pix: é o caso comum, não a exceção")
    void split() {
        cenario();

        pagar(FormaDePagamento.CREDITO, "60");
        var conta = TenantContext.obter(tenant, () -> registrar.executar(comanda,
                DadosDoPagamento.de(FormaDePagamento.PIX, Money.de("40"))));

        assertThat(conta.pagamentos()).hasSize(2);
        assertThat(conta.totalPago()).isEqualTo(Money.de("100"));
        assertThat(conta.restante()).isEqualTo(Money.ZERO);
        assertThat(conta.temSaldoAberto()).isFalse();
    }

    @Test
    @DisplayName("o restante cai a cada forma acrescentada")
    void restante_acompanha() {
        // R-UX-08: "o restante a pagar é recalculado a cada forma adicionada e fica sempre
        // visível". Subtrair no front seria a mesma conta em dois lugares.
        cenario();

        var apos = TenantContext.obter(tenant, () -> registrar.executar(comanda,
                DadosDoPagamento.de(FormaDePagamento.DINHEIRO, Money.de("30"))));

        assertThat(apos.totalPago()).isEqualTo(Money.de("30"));
        assertThat(apos.restante()).isEqualTo(Money.de("70"));
        assertThat(apos.temSaldoAberto()).isTrue();
    }

    @Test
    @DisplayName("cortesia e fiado quitam a comanda, mesmo sem dinheiro entrar")
    void cortesia_quita() {
        // Tratá-las como "não pagamento" deixaria a comanda aberta para sempre, e o salão perderia
        // a única linha que explica por que aquele atendimento não virou dinheiro.
        cenario();

        var conta = TenantContext.obter(tenant, () -> registrar.executar(comanda,
                DadosDoPagamento.de(FormaDePagamento.CORTESIA, Money.de("100"))));

        assertThat(conta.restante()).isEqualTo(Money.ZERO);
        assertThat(conta.pagamentos().get(0).forma().traDinheiro()).isFalse();
    }

    // ------------------------------------------------------------------ o que é recusado

    @Test
    @DisplayName("troco não é pagamento")
    void nao_passa_do_total() {
        // Quem entrega cem numa conta de oitenta pagou oitenta. Registrar cem faria o faturamento
        // do dia crescer sozinho, e o caixa nunca fecharia.
        cenario();

        Throwable erro = catchThrowable(() -> pagar(FormaDePagamento.DINHEIRO, "150"));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-PAGAMENTO_PASSA_DO_TOTAL");
        assertThat(erro).hasMessageContaining("faltam");
    }

    @Test
    @DisplayName("o segundo pagamento também respeita o que falta")
    void segundo_pagamento_respeita_o_restante() {
        cenario();
        pagar(FormaDePagamento.PIX, "90");

        Throwable erro = catchThrowable(() -> pagar(FormaDePagamento.DINHEIRO, "20"));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-PAGAMENTO_PASSA_DO_TOTAL");
    }

    @Test
    @DisplayName("valor zero ou negativo é recusado pelo próprio financeiro")
    void valor_invalido() {
        cenario();

        assertThat(codigoDe(catchThrowable(() -> pagar(FormaDePagamento.DINHEIRO, "0"))))
                .isEqualTo("ER-FIN-DADOS_INVALIDOS");
    }

    @Test
    @DisplayName("só crédito parcela")
    void so_credito_parcela() {
        // Débito em três vezes não existe, e aceitar o número faria a previsão de recebimento
        // dividir um dinheiro que cai de uma vez só.
        cenario();

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> registrar.executar(comanda, new DadosDoPagamento(FormaDePagamento.DEBITO,
                        Money.de("50"), "VISA", 3, null, null))));

        assertThat(codigoDe(erro)).isEqualTo("ER-FIN-SO_CREDITO_PARCELA");
    }

    @Test
    @DisplayName("comanda que não está aberta não recebe pagamento")
    void comanda_fechada() throws SQLException {
        cenario();
        try (var ps = comoOwner().prepareStatement(
                "update comanda set status = 'FECHADA' where id = ?")) {
            ps.setObject(1, comanda);
            ps.executeUpdate();
        }

        Throwable erro = catchThrowable(() -> pagar(FormaDePagamento.DINHEIRO, "50"));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-COMANDA_NAO_ESTA_ABERTA");
    }

    // ------------------------------------------------------------------ conciliação

    @Test
    @DisplayName("cartão nasce pendente de conciliação; dinheiro não")
    void status_de_conciliacao() {
        // Deixar dinheiro e pix como PENDENTE encheria a fila da conciliação com linhas que nunca
        // vão sair de lá — não existe extrato de adquirente para casar com dinheiro.
        cenario();

        pagar(FormaDePagamento.CREDITO, "60");
        pagar(FormaDePagamento.DINHEIRO, "40");
        var conta = TenantContext.obter(tenant, () -> consultar.executar(comanda)).orElseThrow();

        assertThat(conta.pagamentos()).extracting(p -> p.statusConciliacao())
                .containsExactly(StatusDeConciliacao.PENDENTE,
                        StatusDeConciliacao.NAO_APLICAVEL);
    }

    @Test
    @DisplayName("taxa e previsão de recebimento ainda não existem, e nulo não é zero")
    void taxa_ainda_nao_existe() {
        // Zero significaria taxa isenta. Nulo diz "ninguém cadastrou o contrato do adquirente",
        // que é a verdade até a Fase 5.
        cenario();

        pagar(FormaDePagamento.CREDITO, "100");
        var pagamento = TenantContext.obter(tenant,
                () -> consultar.executar(comanda)).orElseThrow().pagamentos().get(0);

        assertThat(pagamento.taxaPrevista()).isNull();
        assertThat(pagamento.previsaoRecebimento()).isNull();
        assertThat(pagamento.parcelas()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ remover

    @Test
    @DisplayName("remover o pagamento devolve o restante")
    void remover_pagamento() {
        cenario();
        var conta = TenantContext.obter(tenant, () -> registrar.executar(comanda,
                DadosDoPagamento.de(FormaDePagamento.PIX, Money.de("100"))));
        UUID pagamentoId = conta.pagamentos().get(0).id();

        var depois = TenantContext.obter(tenant,
                () -> remover.executar(comanda, pagamentoId));

        assertThat(depois.pagamentos()).isEmpty();
        assertThat(depois.restante()).isEqualTo(Money.de("100"));
    }

    @Test
    @DisplayName("pagamento de outra comanda não sai por esta")
    void pagamento_de_outra_comanda() {
        cenario();
        var conta = TenantContext.obter(tenant, () -> registrar.executar(comanda,
                DadosDoPagamento.de(FormaDePagamento.PIX, Money.de("50"))));
        UUID joana = TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                new DadosDoCliente("Joana Prado", null, null, null, null)));
        UUID outra = TenantContext.obter(tenant, () -> abrir.avulsa(joana)).comanda().id();

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> remover.executar(outra, conta.pagamentos().get(0).id())));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-PAGAMENTO_NAO_E_DESTA_COMANDA");
    }

    @Test
    @DisplayName("um salão não vê o pagamento do outro")
    void isolamento_entre_saloes() {
        cenario();
        pagar(FormaDePagamento.PIX, "50");
        UUID comandaDoA = comanda;
        UUID salaoB = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Outro " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        assertThat(TenantContext.obter(salaoB, () -> pagamentos.totalPago(comandaDoA)))
                .isEqualTo(Money.ZERO);
    }
}
