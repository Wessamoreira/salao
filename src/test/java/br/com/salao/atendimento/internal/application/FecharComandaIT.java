package br.com.salao.atendimento.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.atendimento.api.StatusDaComanda;
import br.com.salao.catalogo.api.TipoDeBloco;
import br.com.salao.catalogo.internal.application.CadastrarServicoUseCase;
import br.com.salao.catalogo.internal.application.DefinirPrecoUseCase;
import br.com.salao.catalogo.internal.application.EtapaBruta;
import br.com.salao.cliente.internal.application.CadastrarClienteUseCase;
import br.com.salao.cliente.internal.application.DadosDoCliente;
import br.com.salao.equipe.api.TipoDeVinculo;
import br.com.salao.equipe.internal.application.CadastrarProfissionalUseCase;
import br.com.salao.equipe.internal.application.DadosDoProfissional;
import br.com.salao.estoque.api.FinalidadeDoProduto;
import br.com.salao.estoque.api.UnidadeDeMedida;
import br.com.salao.estoque.internal.application.CadastrarProdutoUseCase;
import br.com.salao.estoque.internal.application.ConsultarSaldoUseCase;
import br.com.salao.estoque.internal.application.DadosDaEntrada;
import br.com.salao.estoque.internal.application.DadosDoProduto;
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

/** RT-ATD-006 — fechar comanda: pagamento, estoque, comissão e evento numa transação. */
@WithMockUser(authorities = { Permissao.COMANDA_ABRIR, Permissao.COMANDA_FECHAR,
        Permissao.CLIENTE_ESCREVER, Permissao.ESTOQUE_LER, Permissao.ESTOQUE_ESCREVER,
        Permissao.PRODUTO_PRECO_ESCREVER, Permissao.CONFIG_GERENCIAR,
        Permissao.USUARIO_GERENCIAR })
class FecharComandaIT extends AbstractPostgresIT {

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarClienteUseCase cadastrarCliente;
    @Autowired private CadastrarProdutoUseCase cadastrarProduto;
    @Autowired private RegistrarEntradaDeLoteUseCase entrada;
    @Autowired private ConsultarSaldoUseCase saldo;
    @Autowired private AbrirComandaUseCase abrir;
    @Autowired private AdicionarItemDeProdutoUseCase adicionarProduto;
    @Autowired private RegistrarPagamentoUseCase pagar;
    @Autowired private FecharComandaUseCase fechar;
    @Autowired private ConsultarComandaUseCase consultar;
    @Autowired private ListarComandasAbertasUseCase abertas;
    @Autowired private CadastrarServicoUseCase cadastrarServico;
    @Autowired private DefinirPrecoUseCase definirPrecoDoServico;
    @Autowired private CadastrarProfissionalUseCase cadastrarProfissional;
    @Autowired private AdicionarItemDeServicoUseCase adicionarServico;

    private UUID tenant;
    private UUID comanda;
    private UUID shampoo;

    /** Um shampoo de R$ 45,00 com 10 em estoque a R$ 20,00, e uma comanda aberta. */
    private void cenario() {
        tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        UUID maria = TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                new DadosDoCliente("Maria Silva", null, null, null, null)));
        shampoo = TenantContext.obter(tenant, () -> cadastrarProduto.executar(new DadosDoProduto(
                "Shampoo", null, FinalidadeDoProduto.VENDA, UnidadeDeMedida.UN,
                Money.de("45")))).id();
        TenantContext.executar(tenant, () -> entrada.executar(shampoo, new DadosDaEntrada(
                new BigDecimal("10"), Money.de("20"), null, LocalDate.now().plusMonths(6), null)));
        comanda = TenantContext.obter(tenant, () -> abrir.avulsa(maria)).comanda().id();
    }

    private void vender(String quantidade) {
        TenantContext.executar(tenant, () -> adicionarProduto.executar(comanda, shampoo,
                new BigDecimal(quantidade)));
    }

    private void pagar(String valor) {
        TenantContext.executar(tenant, () -> pagar.executar(comanda,
                DadosDoPagamento.de(FormaDePagamento.PIX, Money.de(valor))));
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ o caminho completo

    @Test
    @DisplayName("paga, fecha, baixa o estoque e grava o custo da retirada")
    void fechamento_completo() {
        cenario();
        vender("2");
        pagar("90");

        var fechada = TenantContext.obter(tenant, () -> fechar.executar(comanda));

        assertThat(fechada.comanda().status()).isEqualTo(StatusDaComanda.FECHADA);
        assertThat(fechada.comanda().fechadaEm()).isNotNull();
        assertThat(fechada.itens().get(0).valorTotal()).isEqualTo(Money.de("90"));
        assertThat(TenantContext.obter(tenant, () -> saldo.doProduto(shampoo))
                .orElseThrow().quantidade())
                .as("as duas unidades saíram do armário").isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("o custo da retirada chega ao item, e é o do lote de onde saiu")
    void custo_chega_ao_item() throws java.sql.SQLException {
        // custo_medio_unitario era nulo desde RT-ATD-003 esperando por esta rotina. Quem sabe de
        // qual lote a unidade saiu é a baixa.
        cenario();
        vender("2");
        pagar("90");

        TenantContext.executar(tenant, () -> fechar.executar(comanda));

        try (var st = comoOwner().createStatement();
             var rs = st.executeQuery(
                     "select custo_medio_unitario from item_comanda where tipo = 'PRODUTO'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("20.0000");
        }
    }

    @Test
    @DisplayName("comanda vazia fecha: aberta por engano precisa poder sumir do balcão")
    void comanda_vazia_fecha() {
        // RN-ATD-001 dá uma comanda aberta por cliente; uma vazia esquecida bloquearia a próxima
        // dela para sempre, e ficaria na barra de comandas abertas (R-UX-07).
        cenario();

        var fechada = TenantContext.obter(tenant, () -> fechar.executar(comanda));

        assertThat(fechada.comanda().status()).isEqualTo(StatusDaComanda.FECHADA);
        assertThat(fechada.comanda().valorLiquido()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("comanda só de serviço fecha sem tocar no estoque")
    void so_servico_nao_mexe_no_estoque() {
        // O que um serviço consome é ficha técnica, e isso é RT-EST-007. Fechar um corte não pode
        // baixar shampoo nenhum.
        cenario();
        UUID ana = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional("Ana Ribeiro", null, "11144477735",
                        TipoDeVinculo.EMPREGADO, null, null, new BigDecimal("40.00"),
                        null, null, null))).id();
        UUID escova = TenantContext.obter(tenant, () -> cadastrarServico.executar("Escova", null,
                java.util.List.of(new EtapaBruta(TipoDeBloco.ATIVO, 45)))).id();
        TenantContext.executar(tenant, () -> definirPrecoDoServico.executar(escova,
                Money.de("80"), LocalDate.now().minusDays(1)));
        TenantContext.executar(tenant,
                () -> adicionarServico.executar(comanda, escova, ana, null, null));
        pagar("80");

        TenantContext.executar(tenant, () -> fechar.executar(comanda));

        assertThat(TenantContext.obter(tenant, () -> saldo.doProduto(shampoo))
                .orElseThrow().quantidade())
                .as("o estoque não foi tocado").isEqualByComparingTo("10");
    }

    // ------------------------------------------------------------------ o que é recusado

    @Test
    @DisplayName("comanda com saldo aberto não fecha, e a recusa diz quanto falta")
    void saldo_aberto_nao_fecha() {
        // Fechar faria a comanda afirmar que o atendimento foi pago quando não foi, e o
        // faturamento do dia passaria a incluir dinheiro que ninguém recebeu.
        cenario();
        vender("2");
        pagar("50");

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> fechar.executar(comanda)));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-COMANDA_COM_SALDO_ABERTO");
        assertThat(erro).hasMessageContaining("faltam");
    }

    @Test
    @DisplayName("fiado quita e a comanda fecha, mesmo sem dinheiro entrar")
    void fiado_fecha() {
        cenario();
        vender("1");
        TenantContext.executar(tenant, () -> pagar.executar(comanda,
                DadosDoPagamento.de(FormaDePagamento.FIADO, Money.de("45"))));

        var fechada = TenantContext.obter(tenant, () -> fechar.executar(comanda));

        assertThat(fechada.comanda().status()).isEqualTo(StatusDaComanda.FECHADA);
    }

    @Test
    @DisplayName("estoque insuficiente recusa o fechamento INTEIRO")
    void estoque_insuficiente_recusa() {
        // A escolha difícil da rotina: trava o caixa. A alternativa — fechar com estoque negativo
        // — resolveria o minuto e estragaria o mês, porque o inventário passaria a mentir.
        cenario();
        vender("20");
        pagar("900");

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> fechar.executar(comanda)));

        assertThat(codigoDe(erro)).isEqualTo("ER-EST-SALDO_INSUFICIENTE");
        assertThat(erro).hasMessageContaining("há 10");
    }

    @Test
    @DisplayName("recusada a baixa, a comanda continua ABERTA — nada acontece pela metade")
    void falha_na_baixa_desfaz_o_fechamento() {
        // É a pior combinação possível que este teste impede: dinheiro entrou, produto saiu, e o
        // sistema achando que ele ainda está no armário.
        cenario();
        vender("20");
        pagar("900");

        catchThrowable(() -> TenantContext.executar(tenant, () -> fechar.executar(comanda)));

        var depois = TenantContext.obter(tenant, () -> consultar.executar(comanda)).orElseThrow();
        assertThat(depois.comanda().status()).isEqualTo(StatusDaComanda.ABERTA);
        assertThat(depois.comanda().fechadaEm()).isNull();
        assertThat(TenantContext.obter(tenant, () -> saldo.doProduto(shampoo))
                .orElseThrow().quantidade()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("fechar duas vezes é recusado")
    void fechar_duas_vezes() {
        cenario();
        vender("1");
        pagar("45");
        TenantContext.executar(tenant, () -> fechar.executar(comanda));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> fechar.executar(comanda)));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-COMANDA_NAO_ESTA_ABERTA");
        assertThat(TenantContext.obter(tenant, () -> saldo.doProduto(shampoo))
                .orElseThrow().quantidade())
                .as("e o estoque não é baixado duas vezes").isEqualByComparingTo("9");
    }

    @Test
    @DisplayName("comanda fechada não recebe mais item, pagamento nem desconto")
    void fechada_nao_muda_mais() {
        // É isto que congela a comissão: não há passo de snapshot no fechamento — o que congela é
        // a comanda parar de aceitar alteração (RN-ATD-006).
        cenario();
        vender("1");
        pagar("45");
        TenantContext.executar(tenant, () -> fechar.executar(comanda));

        assertThat(codigoDe(catchThrowable(() -> vender("1"))))
                .isEqualTo("ER-ATD-COMANDA_NAO_ESTA_ABERTA");
        assertThat(codigoDe(catchThrowable(() -> pagar("10"))))
                .isEqualTo("ER-ATD-COMANDA_NAO_ESTA_ABERTA");
    }

    @Test
    @DisplayName("a comanda fechada sai da barra do balcão, e a cliente pode abrir outra")
    void sai_da_barra_e_libera_a_cliente() {
        // "Comanda aberta esquecida é dinheiro que sai pela porta" (R-UX-07) — e RN-ATD-001 dá uma
        // aberta por cliente, então fechar é também o que libera a próxima dela.
        cenario();
        UUID cliente = TenantContext.obter(tenant,
                () -> consultar.executar(comanda)).orElseThrow().comanda().clienteId();
        vender("1");
        pagar("45");
        assertThat(TenantContext.obter(tenant, () -> abertas.executar())).hasSize(1);

        TenantContext.executar(tenant, () -> fechar.executar(comanda));

        assertThat(TenantContext.obter(tenant, () -> abertas.executar())).isEmpty();
        var nova = TenantContext.obter(tenant, () -> abrir.avulsa(cliente));
        assertThat(nova.jaEstavaAberta()).isFalse();
        assertThat(nova.comanda().id()).isNotEqualTo(comanda);
    }
}
