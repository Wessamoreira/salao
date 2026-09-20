package br.com.salao.estoque.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

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
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * RT-EST-010 — a régua do salão e o que ela acha dos preços de hoje.
 *
 * <p>A conta em si é provada sem banco por {@code MotorDePrecoTest}. O que esta classe prova é a
 * <strong>fiação</strong>: que o custo médio dos lotes chega ao motor, que os dias sem venda saem dos
 * movimentos, e que gerar de novo substitui.
 */
@WithMockUser(authorities = {Permissao.ESTOQUE_LER, Permissao.ESTOQUE_ESCREVER,
        Permissao.CONFIG_GERENCIAR, Permissao.PRODUTO_PRECO_ESCREVER})
class SugestaoDePrecoIT extends AbstractPostgresIT {

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarProdutoUseCase cadastrarProduto;
    @Autowired private RegistrarEntradaDeLoteUseCase entrada;
    @Autowired private DefinirPoliticaDePrecoUseCase definirPolitica;
    @Autowired private GerarSugestoesDePrecoUseCase gerar;
    @Autowired private ConsultarSugestoesDePrecoUseCase consultar;
    @Autowired private EstoqueApi estoque;
    @Autowired private DefinirPrecoDeVendaUseCase definirPreco;

    private UUID tenant;

    @BeforeEach
    void salao() {
        tenant = novoSalao();
    }

    private UUID novoSalao() {
        return provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
    }

    // ------------------------------------------------------------------ montar a cena

    private UUID produto(String nome, String preco) {
        return produto(tenant, nome, preco);
    }

    private UUID produto(UUID salao, String nome, String preco) {
        return TenantContext.obter(salao, () -> cadastrarProduto.executar(new DadosDoProduto(
                nome, null, FinalidadeDoProduto.VENDA, UnidadeDeMedida.UN,
                preco == null ? null : Money.de(preco)))).id();
    }

    private void receber(UUID produtoId, String quantidade, String custo) {
        receber(tenant, produtoId, quantidade, custo);
    }

    private void receber(UUID salao, UUID produtoId, String quantidade, String custo) {
        TenantContext.executar(salao, () -> entrada.executar(produtoId, new DadosDaEntrada(
                new BigDecimal(quantidade), Money.de(custo), null, null, null)));
    }

    private void politica(String margem) {
        politica(tenant, margem);
    }

    private void politica(UUID salao, String margem) {
        TenantContext.executar(salao, () -> definirPolitica.executar(new BigDecimal(margem)));
    }

    private ConsultarSugestoesDePrecoUseCase.Painel gerarEler() {
        TenantContext.executar(tenant, () -> gerar.executar());
        return TenantContext.obter(tenant, () -> consultar.executar());
    }

    /** Empurra o último movimento do produto para trás, simulando prateleira parada. */
    private void envelhecer(UUID produtoId, int dias) throws SQLException {
        try (var ps = comoOwner().prepareStatement("""
                update movimento_estoque set criado_em = criado_em - (? * interval '1 day')
                 where produto_id = ?""")) {
            ps.setInt(1, dias);
            ps.setObject(2, produtoId);
            ps.executeUpdate();
        }
    }

    private static String codigo(Throwable erro) {
        return ((ErroDeDominio) erro).codigo().codigo();
    }

    // ------------------------------------------------------------------ a régua

    @Test
    @DisplayName("sem margem mínima a rotina recusa: chão escolhido pelo sistema não é chão de ninguém")
    void sem_politica() {
        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant, () -> gerar.executar()));

        assertThat(codigo(erro)).isEqualTo("ER-EST-SEM_POLITICA");
        assertThat(TenantContext.obter(tenant, () -> consultar.executar()).margemMinima()).isNull();
    }

    @Test
    @DisplayName("margem fora da régua é recusada")
    void margem_invalida() {
        for (String margem : List.of("-1", "1001")) {
            Throwable erro = catchThrowable(() -> politica(margem));
            assertThat(codigo(erro)).as(margem).isEqualTo("ER-EST-DADOS_INVALIDOS");
        }
    }

    @Test
    @DisplayName("a margem definida aparece no painel, e redefinir substitui")
    void definir_e_redefinir() {
        politica("30");
        assertThat(TenantContext.obter(tenant, () -> consultar.executar()).margemMinima())
                .isEqualByComparingTo("30");

        politica("45");

        assertThat(TenantContext.obter(tenant, () -> consultar.executar()).margemMinima())
                .isEqualByComparingTo("45");
    }

    // ------------------------------------------------------------------ o que o motor acha

    @Test
    @DisplayName("o custo médio dos lotes chega ao motor e vira a sugestão")
    void custo_medio_chega_ao_motor() {
        politica("30");
        UUID mascara = produto("Máscara 500g", "38.00");
        receber(mascara, "10", "30.00");
        receber(mascara, "10", "32.40");  // média 31,20

        var painel = gerarEler();

        assertThat(painel.itens()).singleElement().satisfies(item -> {
            assertThat(item.produto()).isEqualTo("Máscara 500g");
            assertThat(item.motivo()).isEqualTo("MARGEM_ABAIXO_DO_MINIMO");
            assertThat(item.custoMedio()).isEqualByComparingTo("31.20");
            assertThat(item.precoSugerido()).isEqualByComparingTo("49.90");
            assertThat(item.racional()).contains("R$ 31,20");
            // A redação da IA é de RT-EST-011.
            assertThat(item.explicacao()).isNull();
        });
        assertThat(painel.geradaEm()).isNotNull();
    }

    @Test
    @DisplayName("produto sem estoque não tem custo médio, e não recebe sugestão")
    void sem_estoque_nao_sugere() {
        politica("30");
        produto("Só cadastro", "10.00");

        assertThat(gerarEler().itens()).isEmpty();
    }

    @Test
    @DisplayName("os dias sem venda saem dos movimentos: 60 dias parado com estoque encalha")
    void encalhado_pelos_movimentos() throws SQLException {
        politica("30");
        UUID creme = produto("Creme", "49.90");
        receber(creme, "8", "10.00");
        envelhecer(creme, 70);

        var painel = gerarEler();

        assertThat(painel.itens()).singleElement().satisfies(item -> {
            assertThat(item.motivo()).isEqualTo("ENCALHADO");
            assertThat(item.precoSugerido()).isEqualByComparingTo("19.90");
            assertThat(item.racional()).contains("dias sem vender");
        });
    }

    @Test
    @DisplayName("vender zera o relógio do encalhe")
    void venda_recente_nao_encalha() throws SQLException {
        politica("30");
        UUID creme = produto("Creme", "49.90");
        receber(creme, "8", "10.00");
        envelhecer(creme, 70);
        // Uma venda de hoje: o produto voltou a girar.
        TenantContext.executar(tenant, () -> estoque.baixar(creme, new BigDecimal("1"),
                TipoDeMovimento.VENDA, "ITEM_COMANDA", UUID.randomUUID()));

        assertThat(gerarEler().itens()).noneSatisfy(item ->
                assertThat(item.motivo()).isEqualTo("ENCALHADO"));
    }

    @Test
    @DisplayName("nenhuma sugestão fica abaixo do chão, qualquer que seja a margem")
    void nunca_abaixo_do_chao() {
        politica("30");
        UUID barato = produto("Esmalte", "4.00");
        receber(barato, "20", "3.00");
        UUID caro = produto("Kit", "119.90");
        receber(caro, "5", "40.00");

        var painel = gerarEler();

        assertThat(painel.itens()).isNotEmpty().allSatisfy(item -> {
            BigDecimal chao = item.custoMedio().multiply(new BigDecimal("1.30"));
            assertThat(item.precoSugerido()).as(item.produto()).isGreaterThanOrEqualTo(chao);
        });
    }

    // ------------------------------------------------------------------ gerar de novo

    @Test
    @DisplayName("gerar de novo substitui: o que deixou de se aplicar some")
    void gerar_de_novo_substitui() {
        politica("30");
        UUID mascara = produto("Máscara", "38.00");
        receber(mascara, "10", "31.20");
        assertThat(gerarEler().itens()).hasSize(1);

        // O dono corrige o preço para dentro da régua.
        TenantContext.executar(tenant, () -> definirPreco.executar(mascara, Money.de("49.90")));

        assertThat(gerarEler().itens()).isEmpty();
    }

    @Test
    @DisplayName("gerar duas vezes seguidas não duplica a sugestão do mesmo produto")
    void gerar_duas_vezes() {
        politica("30");
        UUID mascara = produto("Máscara", "38.00");
        receber(mascara, "10", "31.20");

        gerarEler();
        var painel = gerarEler();

        assertThat(painel.itens()).hasSize(1);
    }

    @Test
    @DisplayName("mudar a margem mínima muda a sugestão seguinte")
    void margem_muda_a_sugestao() {
        politica("30");
        UUID mascara = produto("Máscara", "49.90");
        receber(mascara, "10", "31.20");
        assertThat(gerarEler().itens()).isEmpty();

        politica("80");  // 31,20 × 1,80 = 56,16 → 59,90

        assertThat(gerarEler().itens()).singleElement().satisfies(item -> {
            assertThat(item.motivo()).isEqualTo("MARGEM_ABAIXO_DO_MINIMO");
            assertThat(item.precoSugerido()).isEqualByComparingTo("59.90");
        });
    }

    // ------------------------------------------------------------------ as travas

    @Test
    @DisplayName("as sugestões de um salão não aparecem no outro")
    void isolamento() {
        politica("30");
        UUID outro = novoSalao();
        politica(outro, "30");
        UUID doOutro = produto(outro, "Máscara do outro", "38.00");
        receber(outro, doOutro, "10", "31.20");
        TenantContext.executar(outro, () -> gerar.executar());

        assertThat(gerarEler().itens()).isEmpty();
        assertThat(TenantContext.obter(outro, () -> consultar.executar()).itens()).hasSize(1);
    }

    @Test
    @DisplayName("quem não configura não define a régua; quem não lê estoque não vê sugestão")
    @WithMockUser(authorities = Permissao.AGENDA_LER_TODAS)
    void sem_permissao() {
        assertThat(catchThrowable(() -> politica("30")))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(catchThrowable(() -> TenantContext.executar(tenant, () -> gerar.executar())))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(catchThrowable(() -> TenantContext.obter(tenant, () -> consultar.executar())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a sugestão não se edita: decidir sobre ela é RT-EST-011")
    void imutavel() throws SQLException {
        politica("30");
        UUID mascara = produto("Máscara", "38.00");
        receber(mascara, "10", "31.20");
        gerarEler();

        var conexao = comoApp(tenant);
        try (var st = conexao.createStatement()) {
            Throwable erro = catchThrowable(() ->
                    st.executeUpdate("update sugestao_de_preco set preco_sugerido = 1"));
            assertThat(erro).isNotNull();
            assertThat(erro.getMessage()).containsIgnoringCase("permission denied");
        } finally {
            conexao.rollback();
        }
    }
}
