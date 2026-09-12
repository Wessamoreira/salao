package br.com.salao.estoque.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.estoque.api.FinalidadeDoProduto;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-EST-005 — perda, ajuste e devolução com motivo obrigatório. */
@WithMockUser(authorities = { Permissao.ESTOQUE_LER, Permissao.ESTOQUE_ESCREVER,
        Permissao.PRODUTO_PRECO_ESCREVER, Permissao.USUARIO_GERENCIAR })
class PerdaAjusteDevolucaoIT extends AbstractPostgresIT {

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarProdutoUseCase cadastrarProduto;
    @Autowired private RegistrarEntradaDeLoteUseCase entrada;
    @Autowired private ConsultarSaldoUseCase saldo;
    @Autowired private RegistrarPerdaUseCase perda;
    @Autowired private RegistrarAjusteUseCase ajuste;
    @Autowired private DevolverAoFornecedorUseCase devolver;

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

    private BigDecimal saldoAtual() {
        return TenantContext.obter(tenant, () -> saldo.doProduto(shampoo))
                .orElseThrow().quantidade();
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ perda

    @Test
    @DisplayName("a perda sai por FEFO e baixa o saldo")
    void perda_sai_por_fefo() {
        // "Sumiram três shampoos" não diz de qual lote, e é assim que a perda chega no balcão.
        cenario();
        receber("5", "20", LocalDate.now().plusMonths(12));
        receber("5", "30", LocalDate.now().plusMonths(1));

        var baixada = TenantContext.obter(tenant,
                () -> perda.executar(shampoo, new BigDecimal("3"), "caiu e quebrou no lavatório"));

        assertThat(saldoAtual()).isEqualByComparingTo("7");
        assertThat(baixada.parcelas()).singleElement()
                .satisfies(p -> assertThat(p.custoUnitario()).as("saiu do que vence primeiro")
                        .isEqualTo(Money.de("30")));
    }

    @Test
    @DisplayName("perda sem motivo é recusada com frase, antes de tocar o estoque")
    void perda_exige_motivo() {
        cenario();
        receber("5", "20", LocalDate.now().plusMonths(6));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> perda.executar(shampoo, new BigDecimal("2"), "  ")));

        assertThat(codigoDe(erro)).isEqualTo("ER-EST-MOTIVO_OBRIGATORIO");
        assertThat(saldoAtual()).as("nada foi baixado").isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("motivo de uma letra não passa: campo obrigatório que aceita 'x' é preenchido com 'x'")
    void motivo_de_uma_letra() {
        cenario();
        receber("5", "20", LocalDate.now().plusMonths(6));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> perda.executar(shampoo, new BigDecimal("2"), "x")));

        assertThat(codigoDe(erro)).isEqualTo("ER-EST-MOTIVO_OBRIGATORIO");
    }

    // ------------------------------------------------------------------ ajuste

    @Test
    @DisplayName("ajuste negativo tira do lote contado")
    void ajuste_negativo() {
        cenario();
        UUID lote = receber("10", "20", LocalDate.now().plusMonths(6));

        TenantContext.executar(tenant,
                () -> ajuste.executar(lote, new BigDecimal("-3"), "contagem do dia 12"));

        assertThat(saldoAtual()).isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("ajuste positivo entra no CUSTO DO LOTE em que apareceu")
    void ajuste_positivo_usa_o_custo_do_lote() {
        // Atribuir unidades achadas a um lote escolhido por FEFO inventaria um custo, e o custo
        // médio ponderado passaria a mentir a partir dali.
        cenario();
        UUID barato = receber("2", "10", LocalDate.now().plusMonths(12));
        receber("2", "50", LocalDate.now().plusMonths(1));

        TenantContext.executar(tenant,
                () -> ajuste.executar(barato, new BigDecimal("2"), "estavam atrás da prateleira"));

        assertThat(saldoAtual()).isEqualByComparingTo("6");
        // 4 unidades a 10 e 2 a 50 = 140/6.
        assertThat(TenantContext.obter(tenant, () -> saldo.doProduto(shampoo))
                .orElseThrow().custoMedio())
                .isEqualTo(Money.de("23.3333"));
    }

    @Test
    @DisplayName("ajuste não deixa o lote negativo, e a recusa diz quanto há")
    void ajuste_nao_fica_negativo() {
        cenario();
        UUID lote = receber("4", "20", LocalDate.now().plusMonths(6));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> ajuste.executar(lote, new BigDecimal("-9"), "contagem")));

        assertThat(codigoDe(erro)).isEqualTo("ER-EST-SALDO_INSUFICIENTE");
        assertThat(erro).hasMessageContaining("4");
    }

    @Test
    @DisplayName("ajuste de zero é recusado")
    void ajuste_zero() {
        cenario();
        UUID lote = receber("4", "20", LocalDate.now().plusMonths(6));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> ajuste.executar(lote, BigDecimal.ZERO, "contagem bateu")));

        assertThat(codigoDe(erro)).isEqualTo("ER-EST-QUANTIDADE_INVALIDA");
    }

    @Test
    @DisplayName("lote de outro salão não existe para o ajuste")
    void isolamento_entre_saloes() {
        cenario();
        UUID lote = receber("4", "20", LocalDate.now().plusMonths(6));
        UUID salaoB = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Outro " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        Throwable erro = catchThrowable(() -> TenantContext.executar(salaoB,
                () -> ajuste.executar(lote, new BigDecimal("-1"), "contagem")));

        assertThat(codigoDe(erro)).isEqualTo("ER-EST-LOTE_NAO_ENCONTRADO");
    }

    // ------------------------------------------------------------------ devolução

    @Test
    @DisplayName("devolver ao fornecedor tira do lote, com quantidade positiva na entrada")
    void devolucao_ao_fornecedor() {
        // Pedir o sinal a quem chama convidaria a mandar "-3" e AUMENTAR o estoque de um produto
        // que foi embora.
        cenario();
        UUID lote = receber("10", "20", LocalDate.now().plusMonths(6));

        TenantContext.executar(tenant,
                () -> devolver.executar(lote, new BigDecimal("4"), "frascos com lacre violado"));

        assertThat(saldoAtual()).isEqualByComparingTo("6");
    }

    @Test
    @DisplayName("devolver quantidade negativa é recusado")
    void devolucao_negativa_recusada() {
        cenario();
        UUID lote = receber("10", "20", LocalDate.now().plusMonths(6));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> devolver.executar(lote, new BigDecimal("-4"), "lacre violado")));

        assertThat(codigoDe(erro)).isEqualTo("ER-EST-QUANTIDADE_INVALIDA");
    }

    @Test
    @DisplayName("sem estoque:write, nenhum dos três")
    @WithMockUser(authorities = Permissao.ESTOQUE_LER)
    void sem_permissao() {
        UUID salao = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        for (Runnable tentativa : List.<Runnable>of(
                () -> perda.executar(UUID.randomUUID(), BigDecimal.ONE, "qualquer motivo"),
                () -> ajuste.executar(UUID.randomUUID(), BigDecimal.ONE, "qualquer motivo"),
                () -> devolver.executar(UUID.randomUUID(), BigDecimal.ONE, "qualquer motivo"))) {
            assertThatThrownBy(() -> TenantContext.executar(salao, tentativa::run))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    // ------------------------------------------------------------------ o que o BANCO garante

    @Test
    @DisplayName("o banco recusa perda sem motivo, não só o caso de uso")
    void banco_exige_motivo() throws java.sql.SQLException {
        /*
         * É o título da rotina, e por isso ele está no banco.
         *
         * Deixá-lo só no caso de uso significaria que o próximo caminho de escrita — o inventário
         * (RT-EST-006), a ficha técnica (RT-EST-007) — poderia gravar uma perda sem explicação sem
         * nada reclamar.
         */
        cenario();
        UUID lote = receber("5", "20", LocalDate.now().plusMonths(6));

        try (var conexao = comoApp(tenant);
             var ps = conexao.prepareStatement(
                     "insert into movimento_estoque (estabelecimento_id, produto_id, lote_id,"
                             + " tipo, quantidade, custo_unitario, criado_em)"
                             + " values (?, ?, ?, 'PERDA', -1, 20, now())")) {
            ps.setObject(1, tenant);
            ps.setObject(2, shampoo);
            ps.setObject(3, lote);
            Throwable erro = catchThrowable(ps::executeUpdate);
            assertThat(erro).isNotNull();
            assertThat(erro.getMessage()).containsIgnoringCase("movimento_com_motivo");
        }
    }

    @Test
    @DisplayName("movimento de estoque é imutável POR PERMISSÃO, não por disciplina")
    void movimento_e_imutavel() {
        /*
         * O glossário já chamava o movimento de "evento imutável"; a role tinha UPDATE e DELETE.
         * O razão, o vale e o fechamento todos impõem isso pelo banco — o movimento de estoque,
         * que é onde a ideia começou, era o único que dependia de disciplina.
         *
         * Importa mais justamente aqui: perda e ajuste são o que alguém vai querer "consertar" com
         * um UPDATE. A correção certa é um movimento contrário, que deixa rastro dos dois fatos.
         */
        cenario();
        receber("5", "20", LocalDate.now().plusMonths(6));
        TenantContext.executar(tenant,
                () -> perda.executar(shampoo, new BigDecimal("1"), "quebrou na bancada"));

        for (String comando : List.of("update movimento_estoque set quantidade = -99",
                "delete from movimento_estoque")) {
            try (var conexao = comoApp(tenant);
                 var ps = conexao.prepareStatement(comando)) {
                Throwable erro = catchThrowable(ps::executeUpdate);
                assertThat(erro).as("\"%s\" deveria ser recusado", comando).isNotNull();
                assertThat(erro.getMessage()).containsIgnoringCase("permission denied");
            } catch (java.sql.SQLException e) {
                throw new AssertionError(e);
            }
        }
    }
}
