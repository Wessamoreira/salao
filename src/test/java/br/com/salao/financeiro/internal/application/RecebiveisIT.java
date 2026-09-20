package br.com.salao.financeiro.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.financeiro.api.DadosDoPagamento;
import br.com.salao.financeiro.api.FormaDePagamento;
import br.com.salao.financeiro.api.PagamentoApi;
import br.com.salao.financeiro.api.PagamentoResumo;
import br.com.salao.financeiro.internal.domain.CalendarioBancario;
import br.com.salao.financeiro.internal.domain.FaixaDeTaxa;
import br.com.salao.financeiro.internal.domain.MapeamentoDoExtrato;
import br.com.salao.financeiro.internal.domain.TaxaContratada;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * RT-FIN-012 — a taxa contratada, a previsão fotografada e a taxa real no razão.
 *
 * <p>As datas não são conferidas aqui contra um calendário escrito à mão: quem prova o cronograma é
 * {@code RecebimentoTest}, sem banco. O que esta classe prova é a <strong>fiação</strong> — que o
 * contrato chega ao pagamento, que o pagamento chega à tela, e que o extrato chega ao razão.
 */
@WithMockUser(authorities = {Permissao.CONFIG_GERENCIAR, Permissao.FINANCEIRO_LER_TODOS,
        Permissao.FINANCEIRO_CONCILIAR})
class RecebiveisIT extends AbstractPostgresIT {

    private static final String CABECALHO =
            "STONE ID;DATA DA VENDA;VALOR BRUTO;VALOR LIQUIDO;PRODUTO;STATUS";

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private DefinirTaxasDoAdquirenteUseCase definirTaxas;
    @Autowired private ConsultarTaxasDoAdquirenteUseCase consultarTaxas;
    @Autowired private PreverRecebiveisUseCase prever;
    @Autowired private PagamentoApi pagamentos;
    @Autowired private PreverImportacaoUseCase previaDoExtrato;
    @Autowired private ImportarExtratoUseCase importar;
    @Autowired private ConsultarConciliacaoUseCase consultarConciliacao;
    @Autowired private TaxaNoRazao taxaNoRazao;
    @Autowired private PlatformTransactionManager transacoes;
    @Autowired private Relogio relogio;

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

    private static final List<TaxaContratada> CONTRATO = List.of(
            new TaxaContratada(FaixaDeTaxa.DEBITO, new BigDecimal("1.99"), 1),
            new TaxaContratada(FaixaDeTaxa.CREDITO_A_VISTA, new BigDecimal("3.49"), 30),
            new TaxaContratada(FaixaDeTaxa.CREDITO_2_6, new BigDecimal("4.29"), 30),
            new TaxaContratada(FaixaDeTaxa.CREDITO_7_12, new BigDecimal("5.19"), 30));

    private void contratar() {
        contratar(tenant);
    }

    private void contratar(UUID salao) {
        TenantContext.executar(salao, () -> definirTaxas.executar(CONTRATO));
    }

    private PagamentoResumo pagar(FormaDePagamento forma, String valor, Integer parcelas) {
        return TenantContext.obter(tenant, () -> pagamentos.registrar(UUID.randomUUID(),
                new DadosDoPagamento(forma, Money.de(valor), null, parcelas, null, null)));
    }

    private PreverRecebiveisUseCase.Previsao recebiveis() {
        return TenantContext.obter(tenant, () -> prever.executar(null, null));
    }

    private static String venda(String id, String dia, String bruto, String liquido, String status) {
        return String.join(";", id, dia, bruto, liquido, "Crédito", status);
    }

    private ImportarExtratoUseCase.ResultadoDaImportacao importar(String... linhas) {
        byte[] arquivo = (CABECALHO + "\n" + String.join("\n", linhas) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        var previa = TenantContext.obter(tenant, () -> previaDoExtrato.executar(arquivo, null));
        return TenantContext.obter(tenant, () -> importar.executar(arquivo, "vendas.csv",
                new MapeamentoDoExtrato(previa.mapeamento(), previa.formato())));
    }

    /** As linhas de taxa no razão, com sinal e competência. */
    private List<Object[]> taxasNoRazao() throws SQLException {
        try (var ps = comoOwner().prepareStatement("""
                select valor, data_competencia from lancamento
                 where estabelecimento_id = ? and tipo = 'TAXA_ADQUIRENTE'
                 order by criado_em, id""")) {
            ps.setObject(1, tenant);
            try (var rs = ps.executeQuery()) {
                var linhas = new java.util.ArrayList<Object[]>();
                while (rs.next()) {
                    linhas.add(new Object[] {rs.getBigDecimal(1), rs.getDate(2).toLocalDate()});
                }
                return linhas;
            }
        }
    }

    private List<BigDecimal> estornosDeTaxa() throws SQLException {
        try (var ps = comoOwner().prepareStatement("""
                select valor from lancamento
                 where estabelecimento_id = ? and tipo = 'ESTORNO' and origem_tipo = 'ADQUIRENTE'
                 order by criado_em, id""")) {
            ps.setObject(1, tenant);
            try (var rs = ps.executeQuery()) {
                var valores = new java.util.ArrayList<BigDecimal>();
                while (rs.next()) {
                    valores.add(rs.getBigDecimal(1));
                }
                return valores;
            }
        }
    }

    private UUID ultimaImportacao() throws SQLException {
        try (var ps = comoOwner().prepareStatement("""
                select id from importacao_de_extrato where estabelecimento_id = ?
                 order by importada_em desc, id desc limit 1""")) {
            ps.setObject(1, tenant);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private static String codigo(Throwable erro) {
        return ((ErroDeDominio) erro).codigo().codigo();
    }

    // ------------------------------------------------------------------ o cadastro

    @Test
    @DisplayName("as quatro faixas aparecem sempre, cadastradas ou não")
    void cadastro_e_leitura() {
        var antes = TenantContext.obter(tenant, () -> consultarTaxas.executar());
        assertThat(antes).hasSize(4).allSatisfy(f -> assertThat(f.percentual()).isNull());

        contratar();

        assertThat(TenantContext.obter(tenant, () -> consultarTaxas.executar()))
                .hasSize(4)
                .filteredOn(f -> "CREDITO_A_VISTA".equals(f.faixa())).singleElement()
                .satisfies(f -> {
                    assertThat(f.percentual()).isEqualByComparingTo("3.49");
                    assertThat(f.diasParaReceber()).isEqualTo(30);
                    assertThat(f.rotulo()).isEqualTo("Crédito à vista");
                });
    }

    @Test
    @DisplayName("cadastro pela metade é recusado: meia tela vazia não se explica")
    void cadastro_parcial_recusado() {
        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> definirTaxas.executar(List.of(CONTRATO.getFirst()))));

        assertThat(codigo(erro)).isEqualTo("ER-FIN-DADOS_INVALIDOS");
        assertThat(erro).hasMessageContaining("quatro faixas");
    }

    @Test
    @DisplayName("percentual e prazo fora da régua são recusados")
    void valores_invalidos() {
        var comPercentualAbsurdo = List.of(
                new TaxaContratada(FaixaDeTaxa.DEBITO, new BigDecimal("120"), 1),
                CONTRATO.get(1), CONTRATO.get(2), CONTRATO.get(3));
        var comPrazoAbsurdo = List.of(CONTRATO.get(0), CONTRATO.get(1), CONTRATO.get(2),
                new TaxaContratada(FaixaDeTaxa.CREDITO_7_12, new BigDecimal("5.19"), 900));

        for (var contrato : List.of(comPercentualAbsurdo, comPrazoAbsurdo)) {
            Throwable erro = catchThrowable(() ->
                    TenantContext.executar(tenant, () -> definirTaxas.executar(contrato)));
            assertThat(codigo(erro)).isEqualTo("ER-FIN-DADOS_INVALIDOS");
        }
    }

    @Test
    @DisplayName("renegociar substitui, e não acumula")
    void renegociar() {
        contratar();
        var novo = List.of(new TaxaContratada(FaixaDeTaxa.DEBITO, new BigDecimal("1.49"), 1),
                CONTRATO.get(1), CONTRATO.get(2), CONTRATO.get(3));

        TenantContext.executar(tenant, () -> definirTaxas.executar(novo));

        assertThat(TenantContext.obter(tenant, () -> consultarTaxas.executar()))
                .hasSize(4)
                .filteredOn(f -> "DEBITO".equals(f.faixa())).singleElement()
                .satisfies(f -> assertThat(f.percentual()).isEqualByComparingTo("1.49"));
    }

    // ------------------------------------------------------------------ a previsão no pagamento

    @Test
    @DisplayName("o pagamento no cartão nasce com a taxa e o prazo fotografados")
    void previsao_no_pagamento() {
        contratar();
        ZoneId fuso = TenantContext.obter(tenant, () -> ZoneId.of("America/Sao_Paulo"));

        var pago = pagar(FormaDePagamento.CREDITO, "200.00", 1);

        assertThat(pago.taxaPrevista().valor()).isEqualByComparingTo("6.98");
        assertThat(pago.previsaoRecebimento()).isNotNull();
        assertThat(CalendarioBancario.ehDiaUtil(pago.previsaoRecebimento())).isTrue();
        assertThat(pago.previsaoRecebimento())
                .isAfterOrEqualTo(relogio.hojeEm(fuso).plusDays(30));
    }

    @Test
    @DisplayName("a faixa muda com as parcelas: 3x paga a taxa de 2 a 6")
    void faixa_pelas_parcelas() {
        contratar();

        assertThat(pagar(FormaDePagamento.CREDITO, "100.00", 3).taxaPrevista().valor())
                .isEqualByComparingTo("4.29");
        assertThat(pagar(FormaDePagamento.CREDITO, "100.00", 10).taxaPrevista().valor())
                .isEqualByComparingTo("5.19");
        assertThat(pagar(FormaDePagamento.DEBITO, "100.00", 1).taxaPrevista().valor())
                .isEqualByComparingTo("1.99");
    }

    @Test
    @DisplayName("sem contrato não há previsão — e nulo não é zero")
    void sem_contrato_sem_previsao() {
        var pago = pagar(FormaDePagamento.CREDITO, "100.00", 1);

        assertThat(pago.taxaPrevista()).isNull();
        assertThat(pago.previsaoRecebimento()).isNull();
        assertThat(recebiveis().semPrevisao()).isEqualTo(1);
    }

    @Test
    @DisplayName("dinheiro e Pix não têm taxa de adquirente")
    void sem_cartao_sem_taxa() {
        contratar();

        for (FormaDePagamento forma : List.of(FormaDePagamento.DINHEIRO, FormaDePagamento.PIX)) {
            var pago = pagar(forma, "100.00", null);
            assertThat(pago.taxaPrevista()).as(forma.name()).isNull();
        }
        assertThat(recebiveis().semPrevisao()).isZero();
    }

    // ------------------------------------------------------------------ a tela

    @Test
    @DisplayName("a previsão soma por dia, e o líquido é o bruto menos a taxa")
    void previsao_por_dia() {
        contratar();
        pagar(FormaDePagamento.CREDITO, "200.00", 1);
        pagar(FormaDePagamento.DEBITO, "100.00", 1);

        var previsao = recebiveis();

        assertThat(previsao.bruto()).isEqualByComparingTo("300.00");
        assertThat(previsao.taxa()).isEqualByComparingTo("8.97");
        assertThat(previsao.liquido()).isEqualByComparingTo("291.03");
        // Dois dias diferentes: D+1 do débito e D+30 do crédito.
        assertThat(previsao.dias()).hasSize(2);
        assertThat(previsao.dias()).isSortedAccordingTo(
                java.util.Comparator.comparing(PreverRecebiveisUseCase.Dia::dia));
        assertThat(previsao.dias()).allSatisfy(dia ->
                assertThat(dia.liquido()).isEqualByComparingTo(dia.bruto().subtract(dia.taxa())));
    }

    @Test
    @DisplayName("o parcelado vira uma linha por parcela, e a soma fecha com a venda")
    void parcelado_vira_uma_linha_por_parcela() {
        contratar();
        pagar(FormaDePagamento.CREDITO, "300.00", 3);

        var previsao = TenantContext.obter(tenant, () -> prever.executar(null,
                relogio.hojeEm(ZoneId.of("America/Sao_Paulo")).plusDays(120)));

        assertThat(previsao.dias()).hasSize(3);
        assertThat(previsao.bruto()).isEqualByComparingTo("300.00");
        assertThat(previsao.taxa()).isEqualByComparingTo("12.87");
    }

    @Test
    @DisplayName("período invertido ou longo demais é recusado")
    void periodo_invalido() {
        LocalDate hoje = relogio.hojeEm(ZoneId.of("America/Sao_Paulo"));

        assertThat(codigo(catchThrowable(() -> TenantContext.obter(tenant,
                () -> prever.executar(hoje, hoje.minusDays(1))))))
                .isEqualTo("ER-FIN-FILTRO_INVALIDO");
        assertThat(codigo(catchThrowable(() -> TenantContext.obter(tenant,
                () -> prever.executar(hoje, hoje.plusDays(200))))))
                .isEqualTo("ER-FIN-FILTRO_INVALIDO");
    }

    // ------------------------------------------------------------------ a taxa real, no razão

    @Test
    @DisplayName("o líquido do extrato vira taxa real no razão, na competência da venda")
    void taxa_real_no_razao() throws SQLException {
        importar(venda("ST1", "18/09/2026 10:00", "100,00", "96,51", "Aprovada"));

        assertThat(taxasNoRazao()).singleElement().satisfies(linha -> {
            assertThat((BigDecimal) linha[0]).isEqualByComparingTo("-3.49");
            assertThat(linha[1]).isEqualTo(LocalDate.of(2026, 9, 18));
        });
    }

    @Test
    @DisplayName("reimportar o mesmo arquivo não lança a taxa duas vezes")
    void taxa_uma_vez_so() throws SQLException {
        String linha = venda("ST1", "18/09/2026 10:00", "100,00", "96,51", "Aprovada");
        importar(linha);
        importar(linha);

        assertThat(taxasNoRazao()).hasSize(1);
    }

    @Test
    @DisplayName("rodar a taxa de novo sobre a mesma importação não lança duas vezes")
    void taxa_rodar_de_novo() throws SQLException {
        importar(venda("ST1", "18/09/2026 10:00", "100,00", "96,51", "Aprovada"));
        UUID importacaoId = ultimaImportacao();

        // Direto no colaborador, e não por outra importação: a venda repetida nem chega a ele — o
        // que esta prova precisa exercitar é a guarda do "já lançada", que protege a rodada repetida
        // (a mesma propriedade de ConciliacaoIT.rodar_de_novo).
        TenantContext.executar(tenant, () -> new TransactionTemplate(transacoes)
                .executeWithoutResult(s -> taxaNoRazao.lancar(importacaoId, relogio.agora())));

        assertThat(taxasNoRazao()).hasSize(1);
    }

    @Test
    @DisplayName("venda cancelada devolve a taxa: a adquirente não fica com o que reteve")
    void taxa_estornada_quando_cancela() throws SQLException {
        importar(venda("ST1", "18/09/2026 10:00", "100,00", "96,51", "Aprovada"));

        importar(venda("ST1", "18/09/2026 10:00", "100,00", "96,51", "Cancelada"));

        assertThat(taxasNoRazao()).hasSize(1);
        assertThat(estornosDeTaxa()).singleElement()
                .satisfies(v -> assertThat(v).isEqualByComparingTo("3.49"));
    }

    @Test
    @DisplayName("sem líquido no arquivo não há taxa real — inventar seria pior")
    void sem_liquido_sem_lancamento() throws SQLException {
        importar(venda("ST1", "18/09/2026 10:00", "100,00", "", "Aprovada"));

        assertThat(taxasNoRazao()).isEmpty();
    }

    @Test
    @DisplayName("venda isenta não vira linha de zero no razão")
    void taxa_zero_nao_vira_linha() throws SQLException {
        importar(venda("ST1", "18/09/2026 10:00", "100,00", "100,00", "Aprovada"));

        assertThat(taxasNoRazao()).isEmpty();
    }

    // ------------------------------------------------------------------ a conferência da taxa

    @Test
    @DisplayName("taxa cobrada acima do contrato vira aviso, com a diferença somada")
    void taxa_acima_do_contrato() {
        contratar();
        // Contratado 3,49% — R$ 3,49. A Stone reteve R$ 5,00.
        importar(venda("ST1", "18/09/2026 10:00", "100,00", "95,00", "Aprovada"));

        var resumo = TenantContext.obter(tenant, () -> consultarConciliacao.resumir(
                LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 18)));

        assertThat(resumo.taxaAcimaDoContrato().quantidade()).isEqualTo(1);
        assertThat(resumo.taxaAcimaDoContrato().valor()).isEqualByComparingTo("1.51");
    }

    @Test
    @DisplayName("a taxa combinada não vira aviso, nem um centavo de arredondamento")
    void taxa_dentro_do_contrato() {
        contratar();
        importar(venda("ST1", "18/09/2026 10:00", "100,00", "96,51", "Aprovada"),
                // Um centavo a mais é arredondamento da adquirente, e não cobrança a mais.
                venda("ST2", "18/09/2026 11:00", "100,00", "96,50", "Aprovada"));

        var resumo = TenantContext.obter(tenant, () -> consultarConciliacao.resumir(
                LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 18)));

        assertThat(resumo.taxaAcimaDoContrato().quantidade()).isZero();
    }

    @Test
    @DisplayName("sem contrato não há com o que comparar: nenhum aviso")
    void taxa_sem_contrato_nao_avisa() {
        importar(venda("ST1", "18/09/2026 10:00", "100,00", "50,00", "Aprovada"));

        var resumo = TenantContext.obter(tenant, () -> consultarConciliacao.resumir(
                LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 18)));

        assertThat(resumo.taxaAcimaDoContrato().quantidade()).isZero();
    }

    // ------------------------------------------------------------------ as travas

    @Test
    @DisplayName("o contrato de um salão não vale no outro")
    void isolamento() {
        UUID outro = novoSalao();
        contratar(outro);

        var pago = pagar(FormaDePagamento.CREDITO, "100.00", 1);

        assertThat(pago.taxaPrevista()).isNull();
        assertThat(TenantContext.obter(tenant, () -> consultarTaxas.executar()))
                .allSatisfy(f -> assertThat(f.percentual()).isNull());
    }

    @Test
    @DisplayName("quem não configura não cadastra taxa, e quem não lê financeiro não vê recebível")
    @WithMockUser(authorities = Permissao.FINANCEIRO_LER_PROPRIO)
    void sem_permissao() {
        assertThat(catchThrowable(() ->
                TenantContext.executar(tenant, () -> definirTaxas.executar(CONTRATO))))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(catchThrowable(this::recebiveis)).isInstanceOf(AccessDeniedException.class);
    }
}
