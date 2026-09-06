package br.com.salao.atendimento.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.atendimento.api.StatusDaComanda;
import br.com.salao.cliente.internal.application.CadastrarClienteUseCase;
import br.com.salao.cliente.internal.application.DadosDoCliente;
import br.com.salao.estoque.api.FinalidadeDoProduto;
import br.com.salao.estoque.api.UnidadeDeMedida;
import br.com.salao.estoque.internal.application.CadastrarProdutoUseCase;
import br.com.salao.estoque.internal.application.ConsultarSaldoUseCase;
import br.com.salao.estoque.internal.application.DadosDaEntrada;
import br.com.salao.estoque.internal.application.DadosDoProduto;
import br.com.salao.estoque.internal.application.ListarLotesUseCase;
import br.com.salao.estoque.internal.application.RegistrarEntradaDeLoteUseCase;
import br.com.salao.financeiro.api.DadosDoPagamento;
import br.com.salao.financeiro.api.FormaDePagamento;
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
import org.springframework.security.test.context.support.WithMockUser;

/** RT-ATD-007 — reabrir para corrigir, estornar para desfazer. */
@WithMockUser(authorities = { Permissao.COMANDA_ABRIR, Permissao.COMANDA_FECHAR,
        Permissao.COMANDA_REABRIR, Permissao.CLIENTE_ESCREVER, Permissao.ESTOQUE_LER,
        Permissao.ESTOQUE_ESCREVER, Permissao.PRODUTO_PRECO_ESCREVER })
class ReabrirEEstornarIT extends AbstractPostgresIT {

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarClienteUseCase cadastrarCliente;
    @Autowired private CadastrarProdutoUseCase cadastrarProduto;
    @Autowired private RegistrarEntradaDeLoteUseCase entrada;
    @Autowired private ConsultarSaldoUseCase saldo;
    @Autowired private ListarLotesUseCase listarLotes;
    @Autowired private AbrirComandaUseCase abrir;
    @Autowired private AdicionarItemDeProdutoUseCase adicionarProduto;
    @Autowired private RemoverItemUseCase removerItem;
    @Autowired private RegistrarPagamentoUseCase pagar;
    @Autowired private RemoverPagamentoUseCase removerPagamento;
    @Autowired private FecharComandaUseCase fechar;
    @Autowired private ReabrirComandaUseCase reabrir;
    @Autowired private EstornarComandaUseCase estornar;
    @Autowired private ConsultarComandaUseCase consultar;
    @Autowired private ConsultarContaUseCase consultarConta;

    private UUID tenant;
    private UUID comanda;
    private UUID shampoo;
    private UUID loteAntigo;
    private UUID loteNovo;

    /**
     * Dois lotes de propósito: 2 unidades a R$ 20,00 vencendo antes, 8 a R$ 30,00 vencendo depois.
     * Assim a baixa atravessa lotes e a devolução tem de acertar cada um.
     */
    private void cenario() {
        tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        UUID maria = TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                new DadosDoCliente("Maria Silva", null, null, null, null)));
        shampoo = TenantContext.obter(tenant, () -> cadastrarProduto.executar(new DadosDoProduto(
                "Shampoo", null, FinalidadeDoProduto.VENDA, UnidadeDeMedida.UN,
                Money.de("45")))).id();
        loteAntigo = TenantContext.obter(tenant, () -> entrada.executar(shampoo,
                new DadosDaEntrada(new BigDecimal("2"), Money.de("20"), null,
                        LocalDate.now().plusMonths(1), null))).id();
        loteNovo = TenantContext.obter(tenant, () -> entrada.executar(shampoo,
                new DadosDaEntrada(new BigDecimal("8"), Money.de("30"), null,
                        LocalDate.now().plusMonths(6), null))).id();
        comanda = TenantContext.obter(tenant, () -> abrir.avulsa(maria)).comanda().id();
    }

    private void venderEFechar(String quantidade, String valor) {
        TenantContext.executar(tenant, () -> adicionarProduto.executar(comanda, shampoo,
                new BigDecimal(quantidade)));
        TenantContext.executar(tenant, () -> pagar.executar(comanda,
                DadosDoPagamento.de(FormaDePagamento.PIX, Money.de(valor))));
        TenantContext.executar(tenant, () -> fechar.executar(comanda));
    }

    private BigDecimal saldoDoLote(UUID loteId) {
        return TenantContext.obter(tenant, () -> listarLotes.executar(shampoo, false)).stream()
                .filter(l -> l.id().equals(loteId)).findFirst().orElseThrow().quantidade();
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ reabrir

    @Test
    @DisplayName("reabrir devolve as unidades AO LOTE de onde saíram")
    void devolve_ao_lote_certo() {
        // Devolver a um lote genérico faria o custo médio e o FEFO mentirem a partir dali: as
        // unidades saíram de caixas com custos e validades diferentes.
        cenario();
        venderEFechar("3", "135");
        assertThat(saldoDoLote(loteAntigo)).isEqualByComparingTo("0");
        assertThat(saldoDoLote(loteNovo)).isEqualByComparingTo("7");

        TenantContext.executar(tenant, () -> reabrir.executar(comanda, "item lançado errado"));

        assertThat(saldoDoLote(loteAntigo)).as("as duas voltaram para o lote de R$ 20")
                .isEqualByComparingTo("2");
        assertThat(saldoDoLote(loteNovo)).as("e a terceira para o de R$ 30")
                .isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("a devolução é movimento novo, não apaga a baixa")
    void devolucao_e_movimento_novo() throws java.sql.SQLException {
        // RN-EST-006: o razão de estoque não é reescrito, ele ganha a linha que corrige. É aqui
        // que a promessa é cobrada pela primeira vez.
        cenario();
        venderEFechar("2", "90");

        TenantContext.executar(tenant, () -> reabrir.executar(comanda, "corrigir"));

        try (var st = comoOwner().createStatement();
             var rs = st.executeQuery("""
                     select tipo, count(*) from movimento_estoque
                      where tipo in ('VENDA', 'DEVOLUCAO') group by tipo order by tipo
                     """)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("DEVOLUCAO");
            assertThat(rs.getInt(2)).isEqualTo(1);
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).as("a baixa continua lá").isEqualTo("VENDA");
            assertThat(rs.getInt(2)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("reaberta, a comanda volta a aceitar alteração e o custo do item some")
    void volta_a_ser_editavel() {
        cenario();
        venderEFechar("2", "90");

        var reaberta = TenantContext.obter(tenant, () -> reabrir.executar(comanda, "corrigir"));

        assertThat(reaberta.comanda().status()).isEqualTo(StatusDaComanda.ABERTA);
        assertThat(reaberta.comanda().fechadaEm()).isNull();
        TenantContext.executar(tenant, () -> adicionarProduto.executar(comanda, shampoo,
                new BigDecimal("1")));
        assertThat(TenantContext.obter(tenant, () -> consultar.executar(comanda))
                .orElseThrow().itens()).hasSize(2);
    }

    @Test
    @DisplayName("os pagamentos ficam: o dinheiro foi recebido de verdade")
    void pagamentos_ficam() {
        // Apagá-los faria a comanda reaberta parecer não paga, e a recepção cobraria de novo.
        cenario();
        venderEFechar("2", "90");

        TenantContext.executar(tenant, () -> reabrir.executar(comanda, "corrigir"));

        assertThat(TenantContext.obter(tenant, () -> consultar.executar(comanda)).orElseThrow()
                .comanda().valorLiquido()).isEqualTo(Money.de("90"));
        TenantContext.executar(tenant, () -> fechar.executar(comanda));
        assertThat(TenantContext.obter(tenant, () -> consultar.executar(comanda)).orElseThrow()
                .comanda().status()).isEqualTo(StatusDaComanda.FECHADA);
    }

    @Test
    @DisplayName("tirar item de comanda reaberta pode deixar pago a mais — e aí não fecha")
    void pago_a_mais_nao_fecha() {
        // Caso novo criado pela reabertura: enquanto aberta, nenhum pagamento passa do que falta.
        // Fechar assim registraria faturamento maior do que o atendimento.
        cenario();
        venderEFechar("2", "90");
        TenantContext.executar(tenant, () -> reabrir.executar(comanda, "cliente devolveu um"));
        UUID item = TenantContext.obter(tenant, () -> consultar.executar(comanda))
                .orElseThrow().itens().get(0).id();
        TenantContext.executar(tenant, () -> removerItem.executar(comanda, item));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> fechar.executar(comanda)));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-COMANDA_PAGA_A_MAIS");
        assertThat(erro).hasMessageContaining("pagos a mais");
    }

    @Test
    @DisplayName("e o conserto é remover o pagamento — aí a comanda fecha de novo")
    void conserto_do_pago_a_mais() {
        cenario();
        venderEFechar("2", "90");
        TenantContext.executar(tenant, () -> reabrir.executar(comanda, "cliente devolveu tudo"));
        var detalhe = TenantContext.obter(tenant, () -> consultar.executar(comanda)).orElseThrow();
        TenantContext.executar(tenant,
                () -> removerItem.executar(comanda, detalhe.itens().get(0).id()));
        UUID pagamento = TenantContext.obter(tenant, () -> consultarConta.executar(comanda))
                .orElseThrow().pagamentos().get(0).id();

        TenantContext.executar(tenant, () -> removerPagamento.executar(comanda, pagamento));
        var fechada = TenantContext.obter(tenant, () -> fechar.executar(comanda));

        assertThat(fechada.comanda().status()).isEqualTo(StatusDaComanda.FECHADA);
        assertThat(fechada.comanda().valorLiquido()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("reabrir duas vezes não devolve o estoque duas vezes")
    void devolucao_idempotente() {
        // A devolução olha o saldo líquido da origem em cada lote: o que já voltou soma zero e não
        // aparece na lista de pendentes. Não há marca de "já devolvido" para manter em dia.
        cenario();
        venderEFechar("2", "90");
        TenantContext.executar(tenant, () -> reabrir.executar(comanda, "primeira"));
        TenantContext.executar(tenant, () -> fechar.executar(comanda));

        TenantContext.executar(tenant, () -> reabrir.executar(comanda, "segunda"));

        assertThat(TenantContext.obter(tenant, () -> saldo.doProduto(shampoo))
                .orElseThrow().quantidade()).isEqualByComparingTo("10");
    }

    // ------------------------------------------------------------------ estornar

    @Test
    @DisplayName("estornar guarda o motivo na própria comanda: é estado terminal")
    void estorno_guarda_motivo() {
        cenario();
        venderEFechar("2", "90");

        var estornada = TenantContext.obter(tenant,
                () -> estornar.executar(comanda, "cliente desistiu e devolveu"));

        assertThat(estornada.comanda().status()).isEqualTo(StatusDaComanda.ESTORNADA);
        assertThat(TenantContext.obter(tenant, () -> saldo.doProduto(shampoo))
                .orElseThrow().quantidade()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("estornar não apaga: itens e pagamentos ficam como registro")
    void estorno_nao_apaga() {
        // Apagar as linhas faria sumir a única explicação de por que o caixa do dia tem um buraco.
        cenario();
        venderEFechar("2", "90");

        TenantContext.executar(tenant, () -> estornar.executar(comanda, "desistiu"));

        var depois = TenantContext.obter(tenant, () -> consultar.executar(comanda)).orElseThrow();
        assertThat(depois.itens()).hasSize(1);
        assertThat(depois.comanda().valorLiquido()).isEqualTo(Money.de("90"));
    }

    @Test
    @DisplayName("estornada é terminal: não reabre, não estorna de novo")
    void estornada_e_terminal() {
        cenario();
        venderEFechar("2", "90");
        TenantContext.executar(tenant, () -> estornar.executar(comanda, "desistiu"));

        assertThat(codigoDe(catchThrowable(() -> TenantContext.executar(tenant,
                () -> reabrir.executar(comanda, "mudei de ideia")))))
                .isEqualTo("ER-ATD-COMANDA_NAO_ESTA_FECHADA");
        assertThat(codigoDe(catchThrowable(() -> TenantContext.executar(tenant,
                () -> estornar.executar(comanda, "de novo")))))
                .isEqualTo("ER-ATD-COMANDA_NAO_ESTA_FECHADA");
    }

    // ------------------------------------------------------------------ o que é recusado

    @Test
    @DisplayName("comanda aberta não se reabre nem se estorna")
    void aberta_nao_reabre() {
        cenario();

        assertThat(codigoDe(catchThrowable(() -> TenantContext.executar(tenant,
                () -> reabrir.executar(comanda, "motivo")))))
                .isEqualTo("ER-ATD-COMANDA_NAO_ESTA_FECHADA");
        assertThat(codigoDe(catchThrowable(() -> TenantContext.executar(tenant,
                () -> estornar.executar(comanda, "motivo")))))
                .isEqualTo("ER-ATD-COMANDA_NAO_ESTA_FECHADA");
    }

    @Test
    @DisplayName("sem motivo, nenhum dos dois acontece")
    void motivo_obrigatorio() {
        // O motivo é o que separa a correção legítima do desfazimento silencioso — mesma exigência
        // de RT-AGD-005 para cancelar agendamento.
        cenario();
        venderEFechar("2", "90");

        assertThat(codigoDe(catchThrowable(() -> TenantContext.executar(tenant,
                () -> reabrir.executar(comanda, "   ")))))
                .isEqualTo("ER-ATD-MOTIVO_OBRIGATORIO");
        assertThat(codigoDe(catchThrowable(() -> TenantContext.executar(tenant,
                () -> estornar.executar(comanda, null)))))
                .isEqualTo("ER-ATD-MOTIVO_OBRIGATORIO");
        assertThat(TenantContext.obter(tenant, () -> consultar.executar(comanda)).orElseThrow()
                .comanda().status()).isEqualTo(StatusDaComanda.FECHADA);
    }
}
