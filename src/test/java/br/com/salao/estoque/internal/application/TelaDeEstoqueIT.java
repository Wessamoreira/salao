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
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * RT-EST-013 — o que a tela de estoque precisou que existisse.
 *
 * <p>Oito casos de uso estavam sem porta HTTP, e o inventário tinha um beco sem saída: perder o id
 * que {@code abrir} devolveu deixava a contagem viva e inalcançável, com o índice único recusando
 * abrir outra. É RN-EST-040.
 */
@WithMockUser(authorities = { Permissao.ESTOQUE_LER, Permissao.ESTOQUE_ESCREVER,
        Permissao.PRODUTO_PRECO_ESCREVER, Permissao.USUARIO_GERENCIAR })
class TelaDeEstoqueIT extends AbstractPostgresIT {

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarProdutoUseCase cadastrarProduto;
    @Autowired private RegistrarEntradaDeLoteUseCase entrada;
    @Autowired private ConsultarSaldoUseCase saldo;
    @Autowired private ListarLotesUseCase lotes;
    @Autowired private AbrirInventarioUseCase abrir;
    @Autowired private ContarLoteUseCase contar;
    @Autowired private AplicarInventarioUseCase aplicar;
    @Autowired private CancelarInventarioUseCase cancelar;
    @Autowired private ConsultarInventarioAbertoUseCase aberto;
    @Autowired private RegistrarPerdaUseCase perda;
    @Autowired private RegistrarAjusteUseCase ajuste;
    @Autowired private DevolverAoFornecedorUseCase devolucao;

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

    /** @param mesesAteVencer o que separa um lote do outro no FEFO: vence antes, sai antes */
    private UUID receber(String quantidade, String custo, int mesesAteVencer) {
        return TenantContext.obter(tenant, () -> entrada.executar(shampoo, new DadosDaEntrada(
                new BigDecimal(quantidade), Money.de(custo), null,
                LocalDate.now().plusMonths(mesesAteVencer), null))).id();
    }

    private UUID receber(String quantidade, String custo) {
        return receber(quantidade, custo, 6);
    }

    private BigDecimal saldoAtual() {
        return TenantContext.obter(tenant, () -> saldo.doProduto(shampoo))
                .orElseThrow().quantidade();
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ---------------------------------------------------------------- RN-EST-040

    @Test
    @DisplayName("a contagem aberta se acha sem o id — é o que tira a tela do beco sem saída")
    void acha_a_contagem_aberta_sem_o_id() {
        // Perder o id que `abrir` devolveu deixava a contagem viva e inalcançável, e o índice
        // único parcial recusava abrir outra. Para sempre, até alguém mexer no banco.
        cenario();
        UUID lote = receber("10", "20");
        UUID id = TenantContext.obter(tenant, () -> abrir.executar("contagem do mês"));
        TenantContext.executar(tenant, () -> contar.executar(id, lote, new BigDecimal("8"), null));

        var encontrada = TenantContext.obter(tenant, () -> aberto.executar());

        assertThat(encontrada).isPresent();
        assertThat(encontrada.get().id()).isEqualTo(id);
        assertThat(encontrada.get().observacao()).isEqualTo("contagem do mês");
        assertThat(encontrada.get().itens()).hasSize(1);
        assertThat(encontrada.get().quantasDivergem()).isEqualTo(1);
    }

    @Test
    @DisplayName("sem contagem aberta a resposta é vazia, e não erro: é o estado de quase todo dia")
    void sem_contagem_aberta_devolve_vazio() {
        cenario();

        assertThat(TenantContext.obter(tenant, () -> aberto.executar())).isEmpty();
    }

    @Test
    @DisplayName("aplicar libera o próximo: a contagem aberta deixa de existir")
    void aplicar_libera_o_proximo() {
        cenario();
        UUID lote = receber("10", "20");
        UUID id = TenantContext.obter(tenant, () -> abrir.executar(null));
        TenantContext.executar(tenant, () -> contar.executar(id, lote, new BigDecimal("9"), null));

        TenantContext.executar(tenant, () -> aplicar.executar(id));

        assertThat(TenantContext.obter(tenant, () -> aberto.executar())).isEmpty();
        assertThat(saldoAtual()).isEqualByComparingTo("9");
        // E abrir de novo funciona — era isto que o beco sem saída impedia.
        assertThat(TenantContext.obter(tenant, () -> abrir.executar(null))).isNotNull();
    }

    @Test
    @DisplayName("cancelar também libera, e a contagem continua lá para consulta")
    void cancelar_libera_o_proximo() {
        cenario();
        UUID id = TenantContext.obter(tenant, () -> abrir.executar(null));

        TenantContext.executar(tenant, () -> cancelar.executar(id, "recontar amanhã"));

        assertThat(TenantContext.obter(tenant, () -> aberto.executar())).isEmpty();
    }

    @Test
    @DisplayName("a contagem aberta de um salão não é a do outro")
    void contagem_aberta_e_isolada() {
        cenario();
        UUID id = TenantContext.obter(tenant, () -> abrir.executar(null));
        UUID outro = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Outro " + UUID.randomUUID(), null, "Cida",
                "cida-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        assertThat(TenantContext.obter(outro, () -> aberto.executar())).isEmpty();
        assertThat(TenantContext.obter(tenant, () -> aberto.executar()).orElseThrow().id())
                .isEqualTo(id);
    }

    @Test
    @WithMockUser(authorities = Permissao.AGENDA_LER_TODAS)
    @DisplayName("quem não lê estoque não vê a contagem aberta")
    void sem_permissao_nao_ve_contagem() {
        assertThatThrownBy(() -> aberto.executar()).isInstanceOf(AccessDeniedException.class);
    }

    // ---------------------------------------------------------------- perda, ajuste, devolução

    @Test
    @DisplayName("perda é do produto, e o FEFO escolhe de que caixas ela sai")
    void perda_baixa_em_fefo() {
        // Quem viu o xampu vazar no chão sabe o produto e a quantidade, não o lote.
        cenario();
        receber("4", "20", 2);
        receber("10", "22", 9);

        var baixa = TenantContext.obter(tenant, () ->
                perda.executar(shampoo, new BigDecimal("6"), "caixa molhada na chuva"));

        assertThat(baixa.parcelas()).hasSize(2);
        assertThat(saldoAtual()).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("perda sem motivo é recusada, com a frase que diz o que fazer")
    void perda_exige_motivo() {
        cenario();
        receber("10", "20");

        var erro = catchThrowable(() -> TenantContext.executar(tenant, () ->
                perda.executar(shampoo, new BigDecimal("1"), "  ")));

        assertThat(codigoDe(erro)).isEqualTo("ER-EST-MOTIVO_OBRIGATORIO");
        assertThat(saldoAtual()).as("nada saiu").isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("ajuste escreve no lote nomeado, com sinal")
    void ajuste_no_lote() {
        // Quem tem o frasco vencido na mão, com a etiqueta, sabe o lote.
        cenario();
        UUID lote = receber("10", "20");

        TenantContext.executar(tenant, () ->
                ajuste.executar(lote, new BigDecimal("-3"), "três frascos quebrados na estante"));

        assertThat(saldoAtual()).isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("devolução tira do lote e pede motivo")
    void devolucao_no_lote() {
        cenario();
        UUID lote = receber("10", "20");

        TenantContext.executar(tenant, () ->
                devolucao.executar(lote, new BigDecimal("4"), "veio com a tampa rompida"));

        assertThat(saldoAtual()).isEqualByComparingTo("6");
    }

    // ---------------------------------------------------------------- a folha

    @Test
    @DisplayName("contar, conferir e aplicar: a divergência vira ajuste e o saldo passa a bater")
    void contar_conferir_aplicar() {
        cenario();
        UUID lote = receber("10", "20");
        UUID id = TenantContext.obter(tenant, () -> abrir.executar(null));

        TenantContext.executar(tenant, () -> contar.executar(id, lote, new BigDecimal("7"),
                "três não estavam na prateleira"));
        var folha = TenantContext.obter(tenant, () -> aberto.executar()).orElseThrow();

        assertThat(folha.quantasDivergem()).isEqualTo(1);
        assertThat(folha.saldoDasDivergencias()).isEqualByComparingTo("-3");
        assertThat(folha.itens().getFirst().divergencia()).isEqualByComparingTo("-3");
        assertThat(folha.itens().getFirst().bateu()).isFalse();

        assertThat(TenantContext.obter(tenant, () -> aplicar.executar(id))).isEqualTo(1);
        assertThat(saldoAtual()).isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("linha que bateu não vira movimento, e a folha diz isso em número")
    void linha_que_bateu_nao_ajusta() {
        cenario();
        UUID lote = receber("10", "20");
        UUID id = TenantContext.obter(tenant, () -> abrir.executar(null));
        TenantContext.executar(tenant, () -> contar.executar(id, lote, new BigDecimal("10"), null));

        var folha = TenantContext.obter(tenant, () -> aberto.executar()).orElseThrow();
        assertThat(folha.quantasDivergem()).isZero();

        assertThat(TenantContext.obter(tenant, () -> aplicar.executar(id))).isZero();
        assertThat(saldoAtual()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("aplicar o que não cabe recusa tudo e nomeia o produto a recontar")
    void aplicar_que_nao_cabe_nomeia_o_lote() {
        // Com dez lotes conferidos, "saldo insuficiente" sozinho não diz o que recontar.
        cenario();
        UUID lote = receber("10", "20");
        UUID id = TenantContext.obter(tenant, () -> abrir.executar(null));
        TenantContext.executar(tenant, () -> contar.executar(id, lote, new BigDecimal("2"), null));
        // Entre a contagem e o aplicar, vendeu-se quase tudo: o ajuste de −8 não cabe mais.
        TenantContext.executar(tenant, () ->
                ajuste.executar(lote, new BigDecimal("-9"), "saída manual antes de aplicar"));

        var erro = catchThrowable(() -> TenantContext.executar(tenant, () -> aplicar.executar(id)));

        assertThat(erro.getMessage()).contains("Shampoo");
        assertThat(TenantContext.obter(tenant, () -> aberto.executar()))
                .as("continua aberta para ser recontada").isPresent();
    }

    // ---------------------------------------------------------------- o que a tela lê

    @Test
    @DisplayName("saldo e lote saem com dinheiro em número, e sem estoque não há custo médio")
    void saldo_e_lote_para_a_tela() {
        cenario();
        receber("10", "20");

        var comEstoque = TenantContext.obter(tenant, () -> saldo.doProduto(shampoo)).orElseThrow();
        assertThat(comEstoque.custoMedio().valor()).isEqualByComparingTo("20");
        assertThat(comEstoque.valorEmEstoque().valor()).isEqualByComparingTo("200");
        assertThat(TenantContext.obter(tenant, () -> lotes.executar(shampoo, true))).hasSize(1);

        TenantContext.executar(tenant, () ->
                perda.executar(shampoo, new BigDecimal("10"), "vazou a caixa inteira"));

        var vazio = TenantContext.obter(tenant, () -> saldo.doProduto(shampoo)).orElseThrow();
        // Nulo, e não zero: zero diria "tenho unidades que não custaram nada".
        assertThat(vazio.custoMedio()).isNull();
        assertThat(vazio.semEstoque()).isTrue();
    }
}
