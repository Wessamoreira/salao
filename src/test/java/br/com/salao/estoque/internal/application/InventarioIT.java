package br.com.salao.estoque.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.estoque.api.EstoqueApi;
import br.com.salao.estoque.api.FinalidadeDoProduto;
import br.com.salao.estoque.api.InventarioDeEstoque;
import br.com.salao.estoque.api.StatusDoInventario;
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
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-EST-006 — inventário: contagem, divergência e ajuste em lote. */
@WithMockUser(authorities = { Permissao.ESTOQUE_LER, Permissao.ESTOQUE_ESCREVER,
        Permissao.PRODUTO_PRECO_ESCREVER, Permissao.USUARIO_GERENCIAR })
class InventarioIT extends AbstractPostgresIT {

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarProdutoUseCase cadastrarProduto;
    @Autowired private RegistrarEntradaDeLoteUseCase entrada;
    @Autowired private ConsultarSaldoUseCase saldo;
    @Autowired private EstoqueApi estoque;
    @Autowired private AbrirInventarioUseCase abrir;
    @Autowired private ContarLoteUseCase contar;
    @Autowired private ConsultarInventarioUseCase consultar;
    @Autowired private AplicarInventarioUseCase aplicar;
    @Autowired private CancelarInventarioUseCase cancelar;

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

    private void vender(String quantidade) {
        TenantContext.executar(tenant, () -> estoque.baixar(shampoo,
                new BigDecimal(quantidade), TipoDeMovimento.VENDA, "ITEM_COMANDA",
                UUID.randomUUID()));
    }

    private BigDecimal saldoAtual() {
        return TenantContext.obter(tenant, () -> saldo.doProduto(shampoo))
                .orElseThrow().quantidade();
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ o delta

    @Test
    @DisplayName("venda DURANTE a contagem não vira divergência falsa")
    void venda_durante_a_contagem() {
        /*
         * É a asserção que sustenta a rotina.
         *
         * O esperado congela no instante da contagem, e o ajuste é `contado − esperado` — um
         * DELTA. Congelar ao abrir o inventário faria a venda virar divergência: esperava 10,
         * venderam 2, contou 7, e o ajuste sairia −3 quando a divergência real é −1.
         *
         * Com o delta: 10 − 2 (venda) − 3 (ajuste) = 5, que é exatamente 7 − 2. A venda continua
         * valendo em cima da correção.
         */
        cenario();
        UUID lote = receber("10", "20", LocalDate.now().plusMonths(6));
        UUID inventario = TenantContext.obter(tenant, () -> abrir.executar("contagem mensal"));

        // A prateleira tinha 7 quando foi contada.
        TenantContext.executar(tenant,
                () -> contar.executar(inventario, lote, new BigDecimal("7"), null));
        // E duas unidades foram vendidas legitimamente depois disso.
        vender("2");

        int ajustes = TenantContext.obter(tenant, () -> aplicar.executar(inventario));

        assertThat(ajustes).isOne();
        assertThat(saldoAtual()).as("7 contados menos 2 vendidos").isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("linha que bateu não vira movimento")
    void linha_que_bateu() {
        // Movimento de zero não move saldo nenhum e poluiria o histórico de todo lote conferido.
        cenario();
        UUID lote = receber("8", "20", LocalDate.now().plusMonths(6));
        UUID inventario = TenantContext.obter(tenant, () -> abrir.executar(null));

        TenantContext.executar(tenant,
                () -> contar.executar(inventario, lote, new BigDecimal("8"), null));
        int ajustes = TenantContext.obter(tenant, () -> aplicar.executar(inventario));

        assertThat(ajustes).isZero();
        assertThat(saldoAtual()).isEqualByComparingTo("8");
        assertThat(TenantContext.obter(tenant, () -> consultar.executar(inventario))
                .quantasDivergem()).isZero();
    }

    @Test
    @DisplayName("divergência positiva entra no custo do lote contado")
    void divergencia_positiva() {
        cenario();
        UUID barato = receber("2", "10", LocalDate.now().plusMonths(12));
        receber("2", "50", LocalDate.now().plusMonths(1));
        UUID inventario = TenantContext.obter(tenant, () -> abrir.executar(null));

        TenantContext.executar(tenant,
                () -> contar.executar(inventario, barato, new BigDecimal("4"),
                        "estavam atrás da prateleira"));
        TenantContext.executar(tenant, () -> aplicar.executar(inventario));

        assertThat(saldoAtual()).isEqualByComparingTo("6");
        // 4 a 10 e 2 a 50 = 140/6.
        assertThat(TenantContext.obter(tenant, () -> saldo.doProduto(shampoo))
                .orElseThrow().custoMedio()).isEqualTo(Money.de("23.3333"));
    }

    @Test
    @DisplayName("recontar substitui a contagem anterior, com o esperado do novo instante")
    void recontar_substitui() {
        cenario();
        UUID lote = receber("10", "20", LocalDate.now().plusMonths(6));
        UUID inventario = TenantContext.obter(tenant, () -> abrir.executar(null));

        TenantContext.executar(tenant,
                () -> contar.executar(inventario, lote, new BigDecimal("3"), "contei rápido"));
        TenantContext.executar(tenant,
                () -> contar.executar(inventario, lote, new BigDecimal("9"), "recontei"));

        InventarioDeEstoque folha = TenantContext.obter(tenant,
                () -> consultar.executar(inventario));
        assertThat(folha.itens()).as("uma linha, não duas").hasSize(1);
        assertThat(folha.itens().getFirst().contada()).isEqualByComparingTo("9");
        assertThat(folha.itens().getFirst().divergencia()).isEqualByComparingTo("-1");
    }

    @Test
    @DisplayName("contar zero é uma contagem: a prateleira vazia é a divergência que mais importa")
    void contar_zero() {
        cenario();
        UUID lote = receber("6", "20", LocalDate.now().plusMonths(6));
        UUID inventario = TenantContext.obter(tenant, () -> abrir.executar(null));

        TenantContext.executar(tenant,
                () -> contar.executar(inventario, lote, BigDecimal.ZERO, "prateleira vazia"));
        TenantContext.obter(tenant, () -> aplicar.executar(inventario));

        assertThat(saldoAtual()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("contagem negativa é recusada")
    void contagem_negativa() {
        cenario();
        UUID lote = receber("6", "20", LocalDate.now().plusMonths(6));
        UUID inventario = TenantContext.obter(tenant, () -> abrir.executar(null));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> contar.executar(inventario, lote, new BigDecimal("-1"), null)));

        assertThat(codigoDe(erro)).isEqualTo("ER-EST-QUANTIDADE_INVALIDA");
    }

    // ------------------------------------------------------------------ tudo ou nada

    @Test
    @DisplayName("ajuste que não cabe recusa o inventário INTEIRO, dizendo qual produto")
    void ajuste_que_nao_cabe() {
        /*
         * Aplicar parcialmente deixaria o estoque corrigido pela metade, e ninguém saberia qual
         * metade. E a recusa precisa NOMEAR o produto: com dez lotes conferidos, "saldo
         * insuficiente" sozinho não diz o que recontar.
         */
        cenario();
        UUID lote = receber("10", "20", LocalDate.now().plusMonths(6));
        UUID inventario = TenantContext.obter(tenant, () -> abrir.executar(null));
        TenantContext.executar(tenant,
                () -> contar.executar(inventario, lote, new BigDecimal("2"), null));
        // Vendas depois da contagem consomem o que a divergência ia tirar.
        vender("9");

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> aplicar.executar(inventario)));

        assertThat(codigoDe(erro)).isEqualTo("ER-EST-SALDO_INSUFICIENTE");
        assertThat(erro).hasMessageContaining("Shampoo").hasMessageContaining("reconte");
        assertThat(saldoAtual()).as("nada foi aplicado").isEqualByComparingTo("1");
        assertThat(TenantContext.obter(tenant, () -> consultar.executar(inventario)).status())
                .as("o inventário continua aberto para ser recontado")
                .isEqualTo(StatusDoInventario.ABERTO);
    }

    // ------------------------------------------------------------------ um por salão

    @Test
    @DisplayName("um inventário aberto por salão, garantido pelo BANCO")
    void um_aberto_por_salao() {
        // Dois abertos contariam o mesmo lote com esperados diferentes, e aplicar os dois somaria
        // duas correções para uma divergência — o estoque ficaria pior que antes da contagem.
        cenario();
        TenantContext.executar(tenant, () -> abrir.executar("primeira"));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> abrir.executar("segunda")));

        assertThat(codigoDe(erro)).isEqualTo("ER-EST-INVENTARIO_JA_ABERTO");
    }

    @Test
    @DisplayName("aplicar libera o salão para abrir o próximo")
    void aplicar_libera() {
        cenario();
        UUID lote = receber("5", "20", LocalDate.now().plusMonths(6));
        UUID primeiro = TenantContext.obter(tenant, () -> abrir.executar("janeiro"));
        TenantContext.executar(tenant,
                () -> contar.executar(primeiro, lote, new BigDecimal("5"), null));
        TenantContext.obter(tenant, () -> aplicar.executar(primeiro));

        UUID segundo = TenantContext.obter(tenant, () -> abrir.executar("fevereiro"));

        assertThat(segundo).isNotEqualTo(primeiro);
    }

    @Test
    @DisplayName("cancelar libera o salão, e a contagem descartada fica registrada")
    void cancelar_libera() {
        cenario();
        UUID lote = receber("5", "20", LocalDate.now().plusMonths(6));
        UUID primeiro = TenantContext.obter(tenant, () -> abrir.executar("errada"));
        TenantContext.executar(tenant,
                () -> contar.executar(primeiro, lote, new BigDecimal("2"), null));

        TenantContext.executar(tenant,
                () -> cancelar.executar(primeiro, "contei o depósito errado"));

        assertThat(saldoAtual()).as("nada foi escrito no estoque").isEqualByComparingTo("5");
        var descartado = TenantContext.obter(tenant, () -> consultar.executar(primeiro));
        assertThat(descartado.status()).isEqualTo(StatusDoInventario.CANCELADO);
        assertThat(descartado.itens()).as("a contagem fica: é informação").hasSize(1);
        assertThat(TenantContext.obter(tenant, () -> abrir.executar("de novo"))).isNotNull();
    }

    @Test
    @DisplayName("aplicar duas vezes não ajusta duas vezes")
    void aplicar_duas_vezes() {
        cenario();
        UUID lote = receber("10", "20", LocalDate.now().plusMonths(6));
        UUID inventario = TenantContext.obter(tenant, () -> abrir.executar(null));
        TenantContext.executar(tenant,
                () -> contar.executar(inventario, lote, new BigDecimal("7"), null));
        TenantContext.obter(tenant, () -> aplicar.executar(inventario));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> aplicar.executar(inventario)));

        assertThat(codigoDe(erro)).isEqualTo("ER-EST-INVENTARIO_ENCERRADO");
        assertThat(saldoAtual()).isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("contar em inventário encerrado é recusado")
    void contar_em_encerrado() {
        cenario();
        UUID lote = receber("5", "20", LocalDate.now().plusMonths(6));
        UUID inventario = TenantContext.obter(tenant, () -> abrir.executar(null));
        TenantContext.executar(tenant, () -> cancelar.executar(inventario, "desisti"));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> contar.executar(inventario, lote, new BigDecimal("3"), null)));

        assertThat(codigoDe(erro)).isEqualTo("ER-EST-INVENTARIO_ENCERRADO");
    }

    @Test
    @DisplayName("aplicado não se cancela; desfazer é ajuste contrário")
    void aplicado_nao_cancela() {
        cenario();
        UUID lote = receber("5", "20", LocalDate.now().plusMonths(6));
        UUID inventario = TenantContext.obter(tenant, () -> abrir.executar(null));
        TenantContext.executar(tenant,
                () -> contar.executar(inventario, lote, new BigDecimal("4"), null));
        TenantContext.obter(tenant, () -> aplicar.executar(inventario));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> cancelar.executar(inventario, "mudei de ideia")));

        assertThat(codigoDe(erro)).isEqualTo("ER-EST-INVENTARIO_ENCERRADO");
        assertThat(erro).hasMessageContaining("ajuste contrário");
    }

    @Test
    @DisplayName("cancelar exige motivo")
    void cancelar_exige_motivo() {
        cenario();
        UUID inventario = TenantContext.obter(tenant, () -> abrir.executar(null));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> cancelar.executar(inventario, "  ")));

        assertThat(codigoDe(erro)).isEqualTo("ER-EST-MOTIVO_OBRIGATORIO");
    }

    @Test
    @DisplayName("o ajuste do inventário carrega o motivo exigido por RN-EST-014")
    void ajuste_leva_motivo() {
        // O motivo é o próprio inventário: ele amarra o movimento ao documento que tem a
        // contagem, o esperado e quem contou — mais útil que texto livre por linha.
        cenario();
        UUID lote = receber("10", "20", LocalDate.now().plusMonths(6));
        UUID inventario = TenantContext.obter(tenant, () -> abrir.executar(null));
        TenantContext.executar(tenant,
                () -> contar.executar(inventario, lote, new BigDecimal("7"), "caixa amassada"));

        assertThat(TenantContext.obter(tenant, () -> aplicar.executar(inventario))).isOne();
        // Se o motivo não fosse gravado, a constraint `movimento_com_motivo` teria recusado.
        assertThat(saldoAtual()).isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("sem estoque:write, não abre nem aplica")
    @WithMockUser(authorities = Permissao.ESTOQUE_LER)
    void sem_permissao() {
        UUID salao = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        assertThatThrownBy(() -> TenantContext.executar(salao, () -> abrir.executar(null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> TenantContext.executar(salao,
                () -> aplicar.executar(UUID.randomUUID())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("um salão não vê nem conta o inventário do outro")
    void isolamento_entre_saloes() {
        cenario();
        UUID inventario = TenantContext.obter(tenant, () -> abrir.executar("do salão A"));
        UUID salaoB = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Outro " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        Throwable erro = catchThrowable(() -> TenantContext.executar(salaoB,
                () -> consultar.executar(inventario)));

        assertThat(codigoDe(erro)).isEqualTo("ER-EST-INVENTARIO_NAO_ENCONTRADO");
        // E o salão B pode abrir o seu: o índice único é por estabelecimento.
        assertThat(TenantContext.obter(salaoB, () -> abrir.executar("do salão B"))).isNotNull();
    }
}
