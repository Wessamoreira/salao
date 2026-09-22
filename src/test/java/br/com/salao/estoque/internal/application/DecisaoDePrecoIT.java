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
 * RT-EST-011 — o clique humano que fecha a Fase 6.
 *
 * <p>A conta é de RT-EST-010 e já está provada sem banco. Aqui o que se prova é que <strong>nenhum
 * preço muda sem decisão</strong>, que o chão vale também para quem digita, e que a recusa cala.
 */
@WithMockUser(authorities = {Permissao.ESTOQUE_LER, Permissao.ESTOQUE_ESCREVER,
        Permissao.CONFIG_GERENCIAR, Permissao.PRODUTO_PRECO_ESCREVER})
class DecisaoDePrecoIT extends AbstractPostgresIT {

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarProdutoUseCase cadastrarProduto;
    @Autowired private RegistrarEntradaDeLoteUseCase entrada;
    @Autowired private GerarSugestoesDePrecoUseCase gerar;
    @Autowired private ConsultarSugestoesDePrecoUseCase consultar;
    @Autowired private DecidirSugestaoDePrecoUseCase decidir;
    @Autowired private ConsultarDecisoesDePrecoUseCase trilha;
    @Autowired private br.com.salao.estoque.api.ProdutoApi produtos;
    @Autowired private DefinirPrecoDeVendaUseCase definirPreco;

    private UUID tenant;
    private UUID mascara;

    @BeforeEach
    void cenario() throws SQLException {
        tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        // Custo médio 31,20; preço 38,00 dá 21,8% — abaixo dos 30%. Sugere 49,90 na régua.
        mascara = TenantContext.obter(tenant, () -> cadastrarProduto.executar(new DadosDoProduto(
                "Máscara 500g", null, FinalidadeDoProduto.VENDA, UnidadeDeMedida.UN, null)).id());
        TenantContext.executar(tenant, () -> entrada.executar(mascara, new DadosDaEntrada(
                new BigDecimal("10"), Money.de("31.20"), null, null, null)));
        // Preço e política direto no banco: o teste de permissão roda sem `produto:price:write`
        // nem `config:manage`, e o cenário não pode depender delas para existir.
        montar();
    }

    private void montar() throws SQLException {
        try (var ps = comoOwner().prepareStatement(
                "update produto set preco_venda = 38.00 where id = ?")) {
            ps.setObject(1, mascara);
            ps.executeUpdate();
        }
        try (var ps = comoOwner().prepareStatement("""
                insert into politica_de_preco (estabelecimento_id, margem_minima, atualizada_em)
                values (?, 30, now())""")) {
            ps.setObject(1, tenant);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ apoio

    private ConsultarSugestoesDePrecoUseCase.Painel gerarEler() {
        TenantContext.executar(tenant, () -> gerar.executar());
        return TenantContext.obter(tenant, () -> consultar.executar());
    }

    private UUID sugestaoAberta() {
        return gerarEler().itens().getFirst().id();
    }

    private BigDecimal precoDoProduto() {
        return TenantContext.obter(tenant, () -> produtos.porId(mascara)).orElseThrow()
                .precoVenda().valor();
    }

    private List<ConsultarDecisoesDePrecoUseCase.Item> decisoes() {
        return TenantContext.obter(tenant, () -> trilha.listar(null, null)).itens();
    }

    private static String codigo(Throwable erro) {
        return ((ErroDeDominio) erro).codigo().codigo();
    }

    // ------------------------------------------------------------------ aprovar

    @Test
    @DisplayName("aprovar sem mexer aplica o preço sugerido, e a sugestão sai da lista")
    void aprovar_o_sugerido() {
        UUID sugestao = sugestaoAberta();

        TenantContext.executar(tenant, () -> decidir.aprovar(sugestao, null));

        assertThat(precoDoProduto()).isEqualByComparingTo("49.90");
        assertThat(TenantContext.obter(tenant, () -> consultar.executar()).itens()).isEmpty();
    }

    @Test
    @DisplayName("o dono ajusta o número antes de aplicar, e o aplicado difere do sugerido")
    void aprovar_com_ajuste() {
        UUID sugestao = sugestaoAberta();

        TenantContext.executar(tenant, () -> decidir.aprovar(sugestao, Money.de("44.90")));

        assertThat(precoDoProduto()).isEqualByComparingTo("44.90");
        assertThat(decisoes()).singleElement().satisfies(d -> {
            assertThat(d.decisao()).isEqualTo("APLICADA");
            assertThat(d.precoSugerido()).isEqualByComparingTo("49.90");
            assertThat(d.precoAplicado()).isEqualByComparingTo("44.90");
            assertThat(d.precoAnterior()).isEqualByComparingTo("38.00");
        });
    }

    @Test
    @DisplayName("o chão vale para quem digita: abaixo dele a aprovação recusa, com o mínimo na mensagem")
    void chao_vale_para_o_humano() {
        UUID sugestao = sugestaoAberta();

        // 31,20 × 1,30 = 40,56. R$ 39,90 fica abaixo.
        Throwable erro = catchThrowable(() ->
                TenantContext.executar(tenant, () -> decidir.aprovar(sugestao, Money.de("39.90"))));

        assertThat(codigo(erro)).isEqualTo("ER-EST-PRECO_ABAIXO_DO_MINIMO");
        assertThat(erro).hasMessageContaining("40,56").hasMessageContaining("31,20");
        assertThat(precoDoProduto()).isEqualByComparingTo("38.00");
        assertThat(decisoes()).isEmpty();
    }

    @Test
    @DisplayName("exatamente no chão passa: o limite é ficar abaixo")
    void exatamente_no_chao() {
        UUID sugestao = sugestaoAberta();

        TenantContext.executar(tenant, () -> decidir.aprovar(sugestao, Money.de("40.56")));

        assertThat(precoDoProduto()).isEqualByComparingTo("40.56");
    }

    @Test
    @DisplayName("preço zero ou negativo não é preço")
    void preco_invalido() {
        UUID sugestao = sugestaoAberta();

        for (String preco : List.of("0", "-5")) {
            Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                    () -> decidir.aprovar(sugestao, Money.de(preco))));
            assertThat(codigo(erro)).as(preco).isEqualTo("ER-EST-DADOS_INVALIDOS");
        }
    }

    // ------------------------------------------------------------------ recusar

    @Test
    @DisplayName("recusar pede motivo escrito, e o preço fica como estava")
    void recusar() {
        UUID sugestao = sugestaoAberta();

        TenantContext.executar(tenant, () -> decidir.recusar(sugestao, "cliente fiel leva toda semana"));

        assertThat(precoDoProduto()).isEqualByComparingTo("38.00");
        assertThat(TenantContext.obter(tenant, () -> consultar.executar()).itens()).isEmpty();
        assertThat(decisoes()).singleElement().satisfies(d -> {
            assertThat(d.decisao()).isEqualTo("RECUSADA");
            assertThat(d.justificativa()).isEqualTo("cliente fiel leva toda semana");
            assertThat(d.precoAplicado()).isNull();
        });
    }

    @Test
    @DisplayName("sem motivo escrito não recusa")
    void recusa_sem_motivo() {
        UUID sugestao = sugestaoAberta();

        for (String motivo : List.of("", "   ", "ab")) {
            Throwable erro = catchThrowable(() ->
                    TenantContext.executar(tenant, () -> decidir.recusar(sugestao, motivo)));
            assertThat(codigo(erro)).as("[" + motivo + "]").isEqualTo("ER-EST-DADOS_INVALIDOS");
        }
        assertThat(decisoes()).isEmpty();
    }

    @Test
    @DisplayName("a recusa cala o produto: a mesma sugestão não volta na rodada seguinte")
    void recusa_cala() {
        TenantContext.executar(tenant, () -> decidir.recusar(sugestaoAberta(), "não quero mexer"));

        assertThat(gerarEler().itens()).isEmpty();
    }

    @Test
    @DisplayName("custo que sobe muda o preço sugerido, e o fato novo fura o silêncio")
    void fato_novo_fura_o_silencio() {
        TenantContext.executar(tenant, () -> decidir.recusar(sugestaoAberta(), "não quero mexer"));
        assertThat(gerarEler().itens()).isEmpty();

        // Uma caixa bem mais cara: a média sobe e o chão passa de 49,90.
        TenantContext.executar(tenant, () -> entrada.executar(mascara, new DadosDaEntrada(
                new BigDecimal("10"), Money.de("60.00"), null, null, null)));

        assertThat(gerarEler().itens()).singleElement().satisfies(item -> {
            assertThat(item.motivo()).isEqualTo("MARGEM_ABAIXO_DO_MINIMO");
            assertThat(item.precoSugerido()).isNotEqualByComparingTo("49.90");
        });
    }

    @Test
    @DisplayName("aprovar não cala: o produto volta a ser avaliado com o preço novo")
    void aprovar_nao_cala() {
        TenantContext.executar(tenant, () -> decidir.aprovar(sugestaoAberta(), Money.de("44.90")));

        // 44,90 respeita o chão, mas não está na régua: volta como preço quebrado.
        assertThat(gerarEler().itens()).singleElement().satisfies(item ->
                assertThat(item.motivo()).isEqualTo("PRECO_QUEBRADO"));
    }

    @Test
    @DisplayName("preço revertido depois de aprovado volta a aparecer: aprovar não cala")
    void preco_revertido_volta() {
        TenantContext.executar(tenant, () -> decidir.aprovar(sugestaoAberta(), null));
        // Alguém volta o preço antigo pela tela de produto — que sempre aceitou qualquer preço.
        TenantContext.executar(tenant, () -> definirPreco.executar(mascara, Money.de("38.00")));

        // É a mesma sugestão que foi aprovada — mesmo motivo, mesmo preço. Se aprovar calasse,
        // o produto voltaria a vender no prejuízo sem ninguém ver.
        assertThat(gerarEler().itens()).singleElement().satisfies(item -> {
            assertThat(item.motivo()).isEqualTo("MARGEM_ABAIXO_DO_MINIMO");
            assertThat(item.precoSugerido()).isEqualByComparingTo("49.90");
        });
    }

    @Test
    @DisplayName("o silêncio acaba em 90 dias: a mesma sugestão volta")
    void silencio_acaba_em_90_dias() throws SQLException {
        TenantContext.executar(tenant, () -> decidir.recusar(sugestaoAberta(), "não quero mexer"));
        assertThat(gerarEler().itens()).isEmpty();

        try (var ps = comoOwner().prepareStatement("""
                update decisao_de_preco set decidida_em = decidida_em - interval '91 days'
                 where estabelecimento_id = ?""")) {
            ps.setObject(1, tenant);
            ps.executeUpdate();
        }

        assertThat(gerarEler().itens()).singleElement().satisfies(item ->
                assertThat(item.precoSugerido()).isEqualByComparingTo("49.90"));
    }

    // ------------------------------------------------------------------ as travas

    @Test
    @DisplayName("decidir duas vezes a mesma sugestão: a segunda não encontra nada")
    void decidir_duas_vezes() {
        UUID sugestao = sugestaoAberta();
        TenantContext.executar(tenant, () -> decidir.aprovar(sugestao, null));

        Throwable erro = catchThrowable(() ->
                TenantContext.executar(tenant, () -> decidir.recusar(sugestao, "mudei de ideia")));

        assertThat(codigo(erro)).isEqualTo("ER-EST-SUGESTAO_NAO_ENCONTRADA");
        assertThat(decisoes()).hasSize(1);
    }

    @Test
    @DisplayName("a sugestão de outro salão não existe para este")
    void isolamento() {
        UUID outro = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Outro " + UUID.randomUUID(), null, "Dona",
                "outra-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        UUID daqui = sugestaoAberta();

        Throwable erro = catchThrowable(() ->
                TenantContext.executar(outro, () -> decidir.aprovar(daqui, null)));

        assertThat(codigo(erro)).isEqualTo("ER-EST-SUGESTAO_NAO_ENCONTRADA");
        assertThat(precoDoProduto()).isEqualByComparingTo("38.00");
    }

    @Test
    @DisplayName("quem não precifica não decide, mas lê a trilha com estoque:read")
    // Tudo menos `produto:price:write` — inclusive o que o cenário precisa para existir.
    @WithMockUser(authorities = {Permissao.ESTOQUE_LER, Permissao.ESTOQUE_ESCREVER})
    void sem_permissao_de_preco() {
        assertThat(catchThrowable(() -> TenantContext.executar(tenant,
                () -> decidir.aprovar(UUID.randomUUID(), null))))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(catchThrowable(() -> TenantContext.executar(tenant,
                () -> decidir.recusar(UUID.randomUUID(), "não"))))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(decisoes()).isEmpty();
    }

    @Test
    @DisplayName("a decisão não se reescreve nem se apaga")
    void imutavel() throws SQLException {
        TenantContext.executar(tenant, () -> decidir.aprovar(sugestaoAberta(), null));

        for (String sql : List.of("delete from decisao_de_preco",
                "update decisao_de_preco set preco_aplicado = 1")) {
            var conexao = comoApp(tenant);
            try (var st = conexao.createStatement()) {
                Throwable erro = catchThrowable(() -> st.executeUpdate(sql));
                assertThat(erro).as(sql).isNotNull();
                assertThat(erro.getMessage()).containsIgnoringCase("permission denied");
            } finally {
                conexao.rollback();
            }
        }
    }

    @Test
    @DisplayName("a trilha guarda os números congelados e o racional da época")
    void trilha_congela() {
        TenantContext.executar(tenant, () -> decidir.aprovar(sugestaoAberta(), null));

        assertThat(decisoes()).singleElement().satisfies(d -> {
            assertThat(d.produto()).isEqualTo("Máscara 500g");
            assertThat(d.custoMedio()).isEqualByComparingTo("31.20");
            assertThat(d.motivo()).isEqualTo("MARGEM_ABAIXO_DO_MINIMO");
            assertThat(d.racional()).contains("R$\u00a031,20").contains("30,0%");
            assertThat(d.decididaEm()).isNotNull();
        });
    }
}
