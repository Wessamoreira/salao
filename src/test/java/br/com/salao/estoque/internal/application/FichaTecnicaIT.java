package br.com.salao.estoque.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.atendimento.internal.application.AbrirComandaUseCase;
import br.com.salao.atendimento.internal.application.AdicionarItemDeServicoUseCase;
import br.com.salao.atendimento.internal.application.FecharComandaUseCase;
import br.com.salao.atendimento.internal.application.ReabrirComandaUseCase;
import br.com.salao.atendimento.internal.application.RegistrarPagamentoUseCase;
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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-EST-007 — ficha técnica e consumo interno por serviço. */
@WithMockUser(authorities = { Permissao.ESTOQUE_LER, Permissao.ESTOQUE_ESCREVER,
        Permissao.PRODUTO_PRECO_ESCREVER, Permissao.COMANDA_ABRIR, Permissao.COMANDA_FECHAR,
        Permissao.COMANDA_REABRIR, Permissao.CLIENTE_ESCREVER, Permissao.CONFIG_GERENCIAR,
        Permissao.USUARIO_GERENCIAR })
class FichaTecnicaIT extends AbstractPostgresIT {

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarProdutoUseCase cadastrarProduto;
    @Autowired private RegistrarEntradaDeLoteUseCase entrada;
    @Autowired private ConsultarSaldoUseCase saldo;
    @Autowired private CadastrarServicoUseCase cadastrarServico;
    @Autowired private DefinirPrecoUseCase definirPreco;
    @Autowired private CadastrarClienteUseCase cadastrarCliente;
    @Autowired private CadastrarProfissionalUseCase cadastrarProfissional;
    @Autowired private AbrirComandaUseCase abrir;
    @Autowired private AdicionarItemDeServicoUseCase adicionarServico;
    @Autowired private RegistrarPagamentoUseCase pagar;
    @Autowired private FecharComandaUseCase fechar;
    @Autowired private ReabrirComandaUseCase reabrir;
    @Autowired private DefinirItemDaFichaUseCase definirFicha;
    @Autowired private RemoverItemDaFichaUseCase removerFicha;
    @Autowired private ConsultarFichaUseCase consultarFicha;

    private UUID tenant;
    private UUID coloracao;
    private UUID tinta;
    private UUID maria;
    private UUID ana;

    private void cenario() {
        tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        tinta = produto("Tinta 7.0", FinalidadeDoProduto.CONSUMO_INTERNO, null);
        coloracao = TenantContext.obter(tenant, () -> cadastrarServico.executar("Coloração", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 90)))).id();
        TenantContext.executar(tenant, () -> definirPreco.executar(coloracao, Money.de("200"),
                LocalDate.now().minusDays(1)));
        maria = TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                DadosDoCliente.rapido("Maria Silva", "11987654321")));
        ana = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional("Ana Ribeiro", "Ana", "11144477735",
                        TipoDeVinculo.EMPREGADO, null, null, new BigDecimal("30"),
                        null, null, null))).id();
    }

    private UUID produto(String nome, FinalidadeDoProduto finalidade, Money preco) {
        return TenantContext.obter(tenant, () -> cadastrarProduto.executar(new DadosDoProduto(
                nome, null, finalidade, UnidadeDeMedida.ML, preco))).id();
    }

    private void receber(UUID produtoId, String quantidade) {
        TenantContext.executar(tenant, () -> entrada.executar(produtoId, new DadosDaEntrada(
                new BigDecimal(quantidade), Money.de("2"), null,
                LocalDate.now().plusMonths(12), null)));
    }

    private BigDecimal saldoDe(UUID produtoId) {
        return TenantContext.obter(tenant, () -> saldo.doProduto(produtoId))
                .map(s -> s.quantidade()).orElse(BigDecimal.ZERO);
    }

    /** Uma coloração fechada e paga. Devolve o id da comanda. */
    private UUID atender() {
        UUID comanda = TenantContext.obter(tenant, () -> abrir.avulsa(maria)).comanda().id();
        TenantContext.executar(tenant,
                () -> adicionarServico.executar(comanda, coloracao, ana, null, null));
        TenantContext.executar(tenant, () -> pagar.executar(comanda,
                DadosDoPagamento.de(FormaDePagamento.PIX, Money.de("200"))));
        TenantContext.executar(tenant, () -> fechar.executar(comanda));
        return comanda;
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ a ficha

    @Test
    @DisplayName("definir duas vezes substitui, não soma")
    void definir_e_substituir() {
        // Duas linhas do mesmo produto fariam a baixa dobrar sem ninguém pedir.
        cenario();
        TenantContext.executar(tenant,
                () -> definirFicha.executar(coloracao, tinta, new BigDecimal("60")));
        TenantContext.executar(tenant,
                () -> definirFicha.executar(coloracao, tinta, new BigDecimal("90")));

        var ficha = TenantContext.obter(tenant, () -> consultarFicha.executar(coloracao));
        assertThat(ficha).singleElement()
                .satisfies(item -> assertThat(item.quantidade()).isEqualByComparingTo("90"));
    }

    @Test
    @DisplayName("produto só de venda não entra na ficha, e a recusa diz o que fazer")
    void produto_de_venda_recusado() {
        cenario();
        UUID shampooDePrateleira = produto("Shampoo de vitrine", FinalidadeDoProduto.VENDA,
                Money.de("45"));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> definirFicha.executar(coloracao, shampooDePrateleira,
                        new BigDecimal("10"))));

        assertThat(codigoDe(erro)).isEqualTo("ER-EST-PRODUTO_NAO_E_CONSUMIVEL");
        assertThat(erro).hasMessageContaining("AMBOS");
    }

    @Test
    @DisplayName("produto AMBOS entra: é o shampoo que vende e também se usa")
    void produto_ambos_entra() {
        cenario();
        UUID shampoo = produto("Shampoo", FinalidadeDoProduto.AMBOS, Money.de("45"));

        TenantContext.executar(tenant,
                () -> definirFicha.executar(coloracao, shampoo, new BigDecimal("20")));

        assertThat(TenantContext.obter(tenant, () -> consultarFicha.executar(coloracao)))
                .hasSize(1);
    }

    @Test
    @DisplayName("serviço de outro salão não existe para a ficha")
    void servico_de_outro_salao() {
        cenario();
        UUID coloracaoDoA = coloracao;
        UUID salaoB = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Outro " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        Throwable erro = catchThrowable(() -> TenantContext.executar(salaoB,
                () -> definirFicha.executar(coloracaoDoA, tinta, new BigDecimal("60"))));

        assertThat(codigoDe(erro)).isEqualTo("ER-EST-SERVICO_NAO_ENCONTRADO");
    }

    @Test
    @DisplayName("quantidade não positiva é recusada: a ficha diz o que SAI")
    void quantidade_invalida() {
        cenario();

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> definirFicha.executar(coloracao, tinta, BigDecimal.ZERO)));

        assertThat(codigoDe(erro)).isEqualTo("ER-EST-QUANTIDADE_INVALIDA");
    }

    @Test
    @DisplayName("remover o que não está na ficha não reclama")
    void remover_inexistente() {
        cenario();

        TenantContext.executar(tenant, () -> removerFicha.executar(coloracao, tinta));

        assertThat(TenantContext.obter(tenant, () -> consultarFicha.executar(coloracao)))
                .isEmpty();
    }

    // ------------------------------------------------------------------ o consumo

    @Test
    @DisplayName("fechar a comanda baixa o que a ficha manda")
    void fechar_consome() {
        // É o que faz CONSUMO_INTERNO deixar de ser um tipo sem ninguém que o escreva.
        cenario();
        receber(tinta, "500");
        TenantContext.executar(tenant,
                () -> definirFicha.executar(coloracao, tinta, new BigDecimal("60")));

        atender();

        assertThat(saldoDe(tinta)).isEqualByComparingTo("440");
    }

    @Test
    @DisplayName("serviço sem ficha não consome nada, e isso é o caso normal")
    void servico_sem_ficha() {
        // Corte, escova e manicure não têm ficha. Ausência não é configuração pendente.
        cenario();
        receber(tinta, "500");

        atender();

        assertThat(saldoDe(tinta)).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("reabrir a comanda devolve o consumo, sem esta rotina saber que a reabertura existe")
    void reabrir_devolve_o_consumo() {
        // A origem é o item de comanda, a mesma do produto vendido — então
        // `estoque.devolver("ITEM_COMANDA", item)` já reverte os dois.
        cenario();
        receber(tinta, "500");
        TenantContext.executar(tenant,
                () -> definirFicha.executar(coloracao, tinta, new BigDecimal("60")));
        UUID comanda = atender();
        assertThat(saldoDe(tinta)).isEqualByComparingTo("440");

        TenantContext.executar(tenant, () -> reabrir.executar(comanda, "corrigir o tom"));

        assertThat(saldoDe(tinta)).as("a tinta voltou ao lote de onde saiu")
                .isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("insumo em falta NÃO impede o fechamento: o serviço já foi executado")
    void falta_de_insumo_nao_trava_o_balcao() {
        /*
         * A decisão mais importante da rotina.
         *
         * Recusar o fechamento porque o sistema diz que não há tinta pararia o balcão com a
         * cliente na cadeira, por um motivo contábil — e ou o saldo está errado, ou a
         * profissional substituiu o produto. Nos dois casos o atendimento aconteceu.
         *
         * É diferente da venda de produto: ali alguém LANÇOU o item, e vender o que não existe é
         * erro de operação. Aqui ninguém lançou nada — a ficha inferiu.
         */
        cenario();
        receber(tinta, "20");
        TenantContext.executar(tenant,
                () -> definirFicha.executar(coloracao, tinta, new BigDecimal("60")));

        atender();

        assertThat(saldoDe(tinta)).as("baixou o que havia, e não o que a ficha pedia")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("sem nenhum estoque do insumo, o fechamento ainda passa")
    void sem_estoque_nenhum() {
        cenario();
        TenantContext.executar(tenant,
                () -> definirFicha.executar(coloracao, tinta, new BigDecimal("60")));

        atender();

        assertThat(saldoDe(tinta)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("dois insumos na mesma ficha baixam os dois")
    void dois_insumos() {
        cenario();
        UUID ox = produto("Ox 20 volumes", FinalidadeDoProduto.CONSUMO_INTERNO, null);
        receber(tinta, "500");
        receber(ox, "1000");
        TenantContext.executar(tenant,
                () -> definirFicha.executar(coloracao, tinta, new BigDecimal("60")));
        TenantContext.executar(tenant,
                () -> definirFicha.executar(coloracao, ox, new BigDecimal("90")));

        atender();

        assertThat(saldoDe(tinta)).isEqualByComparingTo("440");
        assertThat(saldoDe(ox)).isEqualByComparingTo("910");
    }

    @Test
    @DisplayName("mudar a ficha não mexe em consumo já lançado")
    void ficha_nao_recalcula_o_passado() {
        // A ficha é configuração, não histórico: ela diz o que o serviço consome DE AGORA EM
        // DIANTE. Mesma separação da regra de comissão (RN-FIN-007).
        cenario();
        receber(tinta, "500");
        TenantContext.executar(tenant,
                () -> definirFicha.executar(coloracao, tinta, new BigDecimal("60")));
        atender();

        TenantContext.executar(tenant,
                () -> definirFicha.executar(coloracao, tinta, new BigDecimal("200")));

        assertThat(saldoDe(tinta)).as("os 60 já baixados continuam 60")
                .isEqualByComparingTo("440");
    }

    @Test
    @DisplayName("sem estoque:write, não define ficha")
    @WithMockUser(authorities = Permissao.ESTOQUE_LER)
    void sem_permissao() {
        UUID salao = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        assertThatThrownBy(() -> TenantContext.executar(salao,
                () -> definirFicha.executar(UUID.randomUUID(), UUID.randomUUID(),
                        BigDecimal.ONE)))
                .isInstanceOf(AccessDeniedException.class);
    }
}
