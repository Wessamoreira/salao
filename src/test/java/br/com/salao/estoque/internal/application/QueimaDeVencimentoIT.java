package br.com.salao.estoque.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.estoque.api.FinalidadeDoProduto;
import br.com.salao.estoque.api.UnidadeDeMedida;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * RT-EST-012 — a queima com Postgres de verdade.
 *
 * <p>A conta dos degraus é provada sem banco por {@code QueimaDeVencimentoTest}. Aqui o que se prova é
 * que a validade dos lotes chega ao motor — e que a exceção ao chão vale para a queima e só para ela.
 */
@WithMockUser(authorities = {Permissao.ESTOQUE_LER, Permissao.ESTOQUE_ESCREVER,
        Permissao.CONFIG_GERENCIAR, Permissao.PRODUTO_PRECO_ESCREVER})
class QueimaDeVencimentoIT extends AbstractPostgresIT {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarProdutoUseCase cadastrarProduto;
    @Autowired private RegistrarEntradaDeLoteUseCase entrada;
    @Autowired private DefinirPoliticaDePrecoUseCase definirPolitica;
    @Autowired private GerarSugestoesDePrecoUseCase gerar;
    @Autowired private ConsultarSugestoesDePrecoUseCase consultar;
    @Autowired private DecidirSugestaoDePrecoUseCase decidir;
    @Autowired private ConsultarDecisoesDePrecoUseCase trilha;
    @Autowired private br.com.salao.estoque.api.ProdutoApi produtos;
    @Autowired private Relogio relogio;

    private UUID tenant;
    private LocalDate hoje;

    @BeforeEach
    void salao() {
        tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        TenantContext.executar(tenant, () -> definirPolitica.executar(new BigDecimal("30")));
        hoje = relogio.hojeEm(SP);
    }

    private UUID produto(String nome, String preco) {
        return TenantContext.obter(tenant, () -> cadastrarProduto.executar(new DadosDoProduto(
                nome, null, FinalidadeDoProduto.VENDA, UnidadeDeMedida.UN,
                Money.de(preco)))).id();
    }

    private void lote(UUID produtoId, String quantidade, String custo, LocalDate validade) {
        TenantContext.executar(tenant, () -> entrada.executar(produtoId, new DadosDaEntrada(
                new BigDecimal(quantidade), Money.de(custo), null, validade, null)));
    }

    private ConsultarSugestoesDePrecoUseCase.Painel gerarEler() {
        TenantContext.executar(tenant, () -> gerar.executar());
        return TenantContext.obter(tenant, () -> consultar.executar());
    }

    private static String codigo(Throwable erro) {
        return ((ErroDeDominio) erro).codigo().codigo();
    }

    // ------------------------------------------------------------------ a validade chega ao motor

    @Test
    @DisplayName("lote que vence em 5 dias: a queima vai até o custo médio, na régua")
    void queima_na_ultima_semana() {
        UUID mascara = produto("Máscara 500g", "139.90");
        lote(mascara, "10", "100.00", hoje.plusDays(5));

        assertThat(gerarEler().itens()).singleElement().satisfies(item -> {
            assertThat(item.motivo()).isEqualTo("QUEIMA_DE_VENCIMENTO");
            assertThat(item.rotulo()).isEqualTo("Queima de vencimento");
            assertThat(item.precoSugerido()).isEqualByComparingTo("109.90");
            assertThat(item.racional()).contains("5 dias");
        });
    }

    @Test
    @DisplayName("lote que vence em 40 dias ainda não é queima")
    void fora_da_janela() {
        UUID mascara = produto("Máscara 500g", "159.90");
        lote(mascara, "10", "100.00", hoje.plusDays(40));

        assertThat(gerarEler().itens()).noneSatisfy(item ->
                assertThat(item.motivo()).isEqualTo("QUEIMA_DE_VENCIMENTO"));
    }

    @Test
    @DisplayName("lote vencido não entra na queima — vencido não se vende")
    void vencido_nao_queima() throws SQLException {
        UUID mascara = produto("Máscara 500g", "159.90");
        lote(mascara, "10", "100.00", hoje.plusDays(40));
        // O caso real: o lote chegou válido e o tempo passou. Empurrar a validade no banco é
        // como o teste faz o tempo passar sem esperar 43 dias.
        try (var ps = comoOwner().prepareStatement(
                "update lote set validade = ? where produto_id = ?")) {
            ps.setObject(1, hoje.minusDays(3));
            ps.setObject(2, mascara);
            ps.executeUpdate();
        }

        assertThat(gerarEler().itens()).noneSatisfy(item ->
                assertThat(item.motivo()).isEqualTo("QUEIMA_DE_VENCIMENTO"));
    }

    @Test
    @DisplayName("com dois lotes, manda o válido que vence primeiro")
    void lote_mais_proximo() {
        UUID mascara = produto("Máscara 500g", "159.90");
        lote(mascara, "5", "100.00", hoje.plusDays(60));
        lote(mascara, "5", "100.00", hoje.plusDays(10));

        assertThat(gerarEler().itens()).singleElement().satisfies(item -> {
            assertThat(item.motivo()).isEqualTo("QUEIMA_DE_VENCIMENTO");
            assertThat(item.racional()).contains("10 dias");
        });
    }

    @Test
    @DisplayName("lote sem validade impressa não entra na queima")
    void sem_validade() {
        UUID mascara = produto("Máscara 500g", "159.90");
        lote(mascara, "10", "100.00", null);

        assertThat(gerarEler().itens()).noneSatisfy(item ->
                assertThat(item.motivo()).isEqualTo("QUEIMA_DE_VENCIMENTO"));
    }

    @Test
    @DisplayName("a queima aparece primeiro na lista: é dinheiro que vira zero numa data")
    void queima_primeiro() {
        // Nomes de propósito: em ordem alfabética o de margem viria antes. Quem ordena é o motivo.
        UUID margem = produto("A — margem baixa", "110.00");
        lote(margem, "10", "100.00", null);
        UUID vencendo = produto("Z — vencendo", "139.90");
        lote(vencendo, "10", "100.00", hoje.plusDays(5));

        assertThat(gerarEler().itens()).extracting(i -> i.motivo())
                .containsExactly("QUEIMA_DE_VENCIMENTO", "MARGEM_ABAIXO_DO_MINIMO");
    }

    // ------------------------------------------------------------------ a exceção ao chão

    @Test
    @DisplayName("aprovar a queima abaixo da margem mínima passa, desde que fique no custo ou acima")
    void queima_aprova_abaixo_da_margem() {
        UUID mascara = produto("Máscara 500g", "139.90");
        lote(mascara, "10", "100.00", hoje.plusDays(5));
        UUID sugestao = gerarEler().itens().getFirst().id();

        // 105,00 fica abaixo do chão normal (130,00), e acima do custo.
        TenantContext.executar(tenant, () -> decidir.aprovar(sugestao, Money.de("105.00")));

        assertThat(TenantContext.obter(tenant, () -> produtos.porId(mascara)).orElseThrow()
                .precoVenda().valor()).isEqualByComparingTo("105.00");
    }

    @Test
    @DisplayName("abaixo do custo nem a queima aceita: sem prejuízo é a regra")
    void queima_nao_vai_abaixo_do_custo() {
        UUID mascara = produto("Máscara 500g", "139.90");
        lote(mascara, "10", "100.00", hoje.plusDays(5));
        UUID sugestao = gerarEler().itens().getFirst().id();

        Throwable erro = catchThrowable(() ->
                TenantContext.executar(tenant, () -> decidir.aprovar(sugestao, Money.de("99.90"))));

        assertThat(codigo(erro)).isEqualTo("ER-EST-PRECO_ABAIXO_DO_MINIMO");
        assertThat(erro).hasMessageContaining("custo médio").hasMessageContaining("100,00");
    }

    @Test
    @DisplayName("a exceção é só da queima: o mesmo preço numa sugestão comum é recusado")
    void excecao_so_da_queima() {
        // Sem validade: a sugestão é de margem, e o chão é o de sempre (130,00).
        UUID mascara = produto("Máscara 500g", "110.00");
        lote(mascara, "10", "100.00", null);
        UUID sugestao = gerarEler().itens().getFirst().id();

        Throwable erro = catchThrowable(() ->
                TenantContext.executar(tenant, () -> decidir.aprovar(sugestao, Money.de("105.00"))));

        assertThat(codigo(erro)).isEqualTo("ER-EST-PRECO_ABAIXO_DO_MINIMO");
        assertThat(erro).hasMessageContaining("margem mínima");
    }

    @Test
    @DisplayName("a trilha mostra que a decisão foi de queima — a exceção ao chão fica à vista")
    void trilha_mostra_a_queima() {
        UUID mascara = produto("Máscara 500g", "139.90");
        lote(mascara, "10", "100.00", hoje.plusDays(5));
        UUID sugestao = gerarEler().itens().getFirst().id();

        TenantContext.executar(tenant, () -> decidir.aprovar(sugestao, null));

        assertThat(TenantContext.obter(tenant, () -> trilha.listar(null, null)).itens())
                .singleElement().satisfies(d -> {
                    assertThat(d.motivo()).isEqualTo("QUEIMA_DE_VENCIMENTO");
                    assertThat(d.precoAplicado()).isEqualByComparingTo("109.90");
                });
    }

    @Test
    @DisplayName("produto que vai vencer não recebe sugestão de subir, mesmo abaixo da margem")
    void nao_sobe_o_que_vence() {
        // 38,00 sobre 31,20 está abaixo dos 30%; a 10 dias, a queima daria 39,90 — mais caro. Nada.
        UUID mascara = produto("Máscara 500g", "38.00");
        lote(mascara, "10", "31.20", hoje.plusDays(10));

        assertThat(gerarEler().itens()).isEmpty();
    }
}
