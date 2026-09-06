package br.com.salao.estoque.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.estoque.api.FinalidadeDoProduto;
import br.com.salao.estoque.api.LoteResumo;
import br.com.salao.estoque.api.UnidadeDeMedida;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-EST-002 — a caixa chegou: custo, validade e o movimento que põe no saldo. */
@WithMockUser(authorities = { Permissao.ESTOQUE_LER, Permissao.ESTOQUE_ESCREVER,
        Permissao.PRODUTO_PRECO_ESCREVER })
class EntradaDeLoteIT extends AbstractPostgresIT {

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarProdutoUseCase cadastrarProduto;
    @Autowired private DefinirAtivacaoDoProdutoUseCase ativacao;
    @Autowired private RegistrarEntradaDeLoteUseCase entrada;
    @Autowired private ListarLotesUseCase listarLotes;

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

    private LoteResumo receber(String quantidade, String custo, LocalDate validade) {
        return TenantContext.obter(tenant, () -> entrada.executar(shampoo, new DadosDaEntrada(
                new BigDecimal(quantidade), Money.de(custo), null, validade, null)));
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ o caminho normal

    @Test
    @DisplayName("a caixa entra e o saldo do lote é a projeção do movimento")
    void entrada_cria_lote_e_movimento() {
        cenario();

        var lote = receber("10", "20", LocalDate.now().plusMonths(6));

        assertThat(lote.quantidade()).isEqualByComparingTo("10");
        assertThat(lote.custoUnitario()).isEqualTo(Money.de("20"));
        assertThat(lote.vazio()).isFalse();
        assertThat(lote.recebidoEm()).isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("não existe coluna de saldo: some o movimento, some o saldo")
    void saldo_e_projecao() throws SQLException {
        // A prova de que o saldo não está gravado em lugar nenhum. Se houvesse coluna, apagar o
        // movimento deixaria o lote dizendo que ainda tem dez.
        cenario();
        var lote = receber("10", "20", null);
        try (var ps = comoOwner().prepareStatement(
                "delete from movimento_estoque where lote_id = ?")) {
            ps.setObject(1, lote.id());
            ps.executeUpdate();
        }

        var depois = TenantContext.obter(tenant, () -> listarLotes.executar(shampoo, false));

        assertThat(depois).hasSize(1);
        assertThat(depois.get(0).quantidade()).isEqualByComparingTo("0");
        assertThat(depois.get(0).vazio()).isTrue();
    }

    @Test
    @DisplayName("duas caixas do mesmo produto são dois lotes, com custos diferentes")
    void dois_lotes() {
        // É por isso que custo não é coluna do produto: a mesma prateleira tem unidades que
        // custaram vinte e unidades que custaram vinte e cinco.
        cenario();

        receber("10", "20", LocalDate.now().plusMonths(6));
        receber("5", "25", LocalDate.now().plusMonths(3));
        var lotes = TenantContext.obter(tenant, () -> listarLotes.executar(shampoo, true));

        assertThat(lotes).hasSize(2);
        assertThat(lotes).extracting(l -> l.custoUnitario().valor().stripTrailingZeros()
                .toPlainString()).containsExactly("25", "20");
    }

    @Test
    @DisplayName("a ordem é a do FEFO: vence primeiro sai primeiro, e o sem validade vai por último")
    void ordem_fefo() {
        // A ordem vem pronta do banco porque é a mesma que RT-EST-004 vai consumir. Deixar cada
        // chamador reinventar o critério é como metade das implementações de FEFO erra.
        cenario();
        receber("1", "20", null);
        receber("1", "20", LocalDate.now().plusMonths(12));
        receber("1", "20", LocalDate.now().plusMonths(1));

        var lotes = TenantContext.obter(tenant, () -> listarLotes.executar(shampoo, true));

        assertThat(lotes).extracting(LoteResumo::validade)
                .containsExactly(LocalDate.now().plusMonths(1),
                        LocalDate.now().plusMonths(12), null);
    }

    @Test
    @DisplayName("custo zero é válido: amostra do fornecedor chega sem custar nada")
    void custo_zero() {
        cenario();

        var lote = receber("3", "0", null);

        assertThat(lote.custoUnitario()).isEqualTo(Money.ZERO);
        assertThat(lote.quantidade()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("caixa vencida entra: é o caso que MAIS precisa de registro")
    void vencida_entra() {
        // Recusar impediria justamente registrar a caixa que chegou vencida e vai voltar para o
        // fornecedor. O sistema registra o que aconteceu.
        cenario();

        var lote = receber("2", "20", LocalDate.now().minusDays(1));

        assertThat(lote.vencidoEm(LocalDate.now())).isTrue();
        assertThat(lote.quantidade()).isEqualByComparingTo("2");
    }

    // ------------------------------------------------------------------ o que é recusado

    @Test
    @DisplayName("quantidade zero ou negativa não entra")
    void quantidade_invalida() {
        // Zero não move nada; negativo é saída, que é outra rotina e exige motivo (RT-EST-005).
        cenario();

        assertThat(codigoDe(catchThrowable(() -> receber("0", "20", null))))
                .isEqualTo("ER-EST-QUANTIDADE_INVALIDA");
        assertThat(codigoDe(catchThrowable(() -> receber("-5", "20", null))))
                .isEqualTo("ER-EST-QUANTIDADE_INVALIDA");
    }

    @Test
    @DisplayName("custo negativo não é custo")
    void custo_negativo() {
        cenario();

        assertThat(codigoDe(catchThrowable(() -> receber("1", "-1", null))))
                .isEqualTo("ER-EST-DADOS_INVALIDOS");
    }

    @Test
    @DisplayName("produto desativado não recebe entrada")
    void produto_inativo() {
        // Receber mercadoria de um produto tirado de circulação é quase sempre o cadastro errado
        // escolhido na tela — e descobrir isso depois custa um inventário.
        cenario();
        TenantContext.executar(tenant, () -> ativacao.executar(shampoo, false));

        assertThat(codigoDe(catchThrowable(() -> receber("10", "20", null))))
                .isEqualTo("ER-EST-PRODUTO_INATIVO");
    }

    @Test
    @DisplayName("produto que não existe")
    void produto_inexistente() {
        cenario();

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> entrada.executar(UUID.randomUUID(), new DadosDaEntrada(
                        BigDecimal.ONE, Money.de("20"), null, null, null))));

        assertThat(codigoDe(erro)).isEqualTo("ER-EST-PRODUTO_NAO_ENCONTRADO");
    }

    @Test
    @DisplayName("um salão não vê o lote do outro")
    void isolamento_entre_saloes() {
        cenario();
        receber("10", "20", null);
        UUID produtoDoA = shampoo;
        UUID salaoB = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Outro " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        assertThat(TenantContext.obter(salaoB, () -> listarLotes.executar(produtoDoA, false)))
                .isEmpty();
    }
}
