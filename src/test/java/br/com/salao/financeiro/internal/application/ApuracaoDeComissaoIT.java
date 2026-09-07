package br.com.salao.financeiro.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.atendimento.internal.application.AbrirComandaUseCase;
import br.com.salao.atendimento.internal.application.AdicionarItemDeProdutoUseCase;
import br.com.salao.atendimento.internal.application.AdicionarItemDeServicoUseCase;
import br.com.salao.atendimento.internal.application.EstornarComandaUseCase;
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
import br.com.salao.estoque.api.FinalidadeDoProduto;
import br.com.salao.estoque.api.UnidadeDeMedida;
import br.com.salao.estoque.internal.application.CadastrarProdutoUseCase;
import br.com.salao.estoque.internal.application.DadosDaEntrada;
import br.com.salao.estoque.internal.application.DadosDoProduto;
import br.com.salao.estoque.internal.application.RegistrarEntradaDeLoteUseCase;
import br.com.salao.equipe.internal.application.CadastrarProfissionalUseCase;
import br.com.salao.equipe.internal.application.DadosDoProfissional;
import br.com.salao.financeiro.api.DadosDoPagamento;
import br.com.salao.financeiro.api.FormaDePagamento;
import br.com.salao.financeiro.api.Lancamento;
import br.com.salao.financeiro.api.RazaoApi;
import br.com.salao.financeiro.api.TipoDeLancamento;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-FIN-003 — apurar comissão no fechamento da comanda. */
@WithMockUser(authorities = { Permissao.COMANDA_ABRIR, Permissao.COMANDA_FECHAR,
        Permissao.COMANDA_REABRIR, Permissao.CLIENTE_ESCREVER, Permissao.CONFIG_GERENCIAR,
        Permissao.USUARIO_GERENCIAR, Permissao.ESTOQUE_LER, Permissao.ESTOQUE_ESCREVER,
        Permissao.PRODUTO_PRECO_ESCREVER })
class ApuracaoDeComissaoIT extends AbstractPostgresIT {

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarClienteUseCase cadastrarCliente;
    @Autowired private CadastrarProfissionalUseCase cadastrarProfissional;
    @Autowired private CadastrarServicoUseCase cadastrarServico;
    @Autowired private DefinirPrecoUseCase definirPreco;
    @Autowired private AbrirComandaUseCase abrir;
    @Autowired private AdicionarItemDeServicoUseCase adicionarServico;
    @Autowired private AdicionarItemDeProdutoUseCase adicionarProduto;
    @Autowired private RegistrarPagamentoUseCase pagar;
    @Autowired private FecharComandaUseCase fechar;
    @Autowired private ReabrirComandaUseCase reabrir;
    @Autowired private EstornarComandaUseCase estornar;
    @Autowired private RazaoApi razao;
    @Autowired private CadastrarProdutoUseCase cadastrarProduto;
    @Autowired private RegistrarEntradaDeLoteUseCase entradaDeLote;

    private UUID tenant;
    private UUID ana;
    private UUID coloracao;
    private UUID comanda;

    /** Coloração de R$ 200,00 com a Ana a 30%, numa comanda aberta. */
    private void cenario() {
        tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        UUID maria = TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                new DadosDoCliente("Maria Silva", null, null, null, null)));
        ana = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional("Ana Ribeiro", null, "11144477735",
                        TipoDeVinculo.EMPREGADO, null, null, new BigDecimal("30"),
                        null, null, null))).id();
        coloracao = TenantContext.obter(tenant, () -> cadastrarServico.executar("Coloração", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 90)))).id();
        TenantContext.executar(tenant, () -> definirPreco.executar(coloracao, Money.de("200"),
                LocalDate.now().minusDays(1)));
        comanda = TenantContext.obter(tenant, () -> abrir.avulsa(maria)).comanda().id();
    }

    private void lancarEFechar() {
        TenantContext.executar(tenant,
                () -> adicionarServico.executar(comanda, coloracao, ana, null, null));
        TenantContext.executar(tenant, () -> pagar.executar(comanda,
                DadosDoPagamento.de(FormaDePagamento.PIX, Money.de("200"))));
        TenantContext.executar(tenant, () -> fechar.executar(comanda));
    }

    private List<Lancamento> lancamentos() {
        return TenantContext.obter(tenant, () -> razao.daOrigem("COMANDA", comanda));
    }

    // ------------------------------------------------------------------ a apuração

    @Test
    @DisplayName("fechar escreve receita positiva e comissão negativa")
    void receita_e_comissao() {
        // O sinal é o que define débito e crédito: +200 de receita e −60 de comissão deixam o
        // salão com 140 sem ninguém precisar saber quais tipos somam.
        cenario();

        lancarEFechar();

        var linhas = lancamentos();
        assertThat(linhas).hasSize(2);
        assertThat(linhas).filteredOn(l -> l.tipo() == TipoDeLancamento.RECEITA_SERVICO)
                .singleElement()
                .satisfies(l -> {
                    assertThat(l.valor()).isEqualTo(Money.de("200"));
                    assertThat(l.contraparteTipo()).isEqualTo("SALAO");
                });
        assertThat(linhas).filteredOn(l -> l.tipo() == TipoDeLancamento.COMISSAO)
                .singleElement()
                .satisfies(l -> {
                    assertThat(l.valor()).isEqualTo(Money.de("-60"));
                    assertThat(l.contraparteId()).isEqualTo(ana);
                });
    }

    @Test
    @DisplayName("a descrição da comissão carrega o percentual e a origem")
    void descricao_explica_o_percentual() {
        // É o que faz o extrato responder "por que 30%?" sem consultar regra nenhuma — inclusive
        // depois que a regra deixar de existir.
        cenario();

        lancarEFechar();

        assertThat(lancamentos()).filteredOn(l -> l.tipo() == TipoDeLancamento.COMISSAO)
                .singleElement()
                .satisfies(l -> assertThat(l.descricao())
                        .contains("Coloração").contains("30").contains("PROFISSIONAL"));
    }

    @Test
    @DisplayName("comanda só de produto gera receita e nenhuma comissão")
    void produto_nao_gera_comissao() {
        // Produto paga 0% por padrão (pergunta 3), e item de produto não tem executante
        // (RN-ATD-018): não há de quem cobrar comissão.
        cenario();
        UUID shampoo = TenantContext.obter(tenant, () -> cadastrarProdutoComEstoque());
        TenantContext.executar(tenant, () -> adicionarProduto.executar(comanda, shampoo, null));
        TenantContext.executar(tenant, () -> pagar.executar(comanda,
                DadosDoPagamento.de(FormaDePagamento.PIX, Money.de("45"))));
        TenantContext.executar(tenant, () -> fechar.executar(comanda));

        var linhas = lancamentos();
        assertThat(linhas).singleElement()
                .satisfies(l -> assertThat(l.tipo()).isEqualTo(TipoDeLancamento.RECEITA_PRODUTO));
    }

    @Test
    @DisplayName("comissão de zero não vira linha")
    void comissao_zero_nao_vira_linha() {
        // Ela não move saldo nenhum e poluiria o extrato de quem trabalha por salário fixo. Por
        // que foi zero está no executante, com a origem que a cascata gravou.
        tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        UUID maria = TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                new DadosDoCliente("Maria Silva", null, null, null, null)));
        ana = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional("Ana Ribeiro", null, "11144477735",
                        TipoDeVinculo.EMPREGADO, null, null, BigDecimal.ZERO,
                        null, null, null))).id();
        coloracao = TenantContext.obter(tenant, () -> cadastrarServico.executar("Coloração", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 90)))).id();
        TenantContext.executar(tenant, () -> definirPreco.executar(coloracao, Money.de("200"),
                LocalDate.now().minusDays(1)));
        comanda = TenantContext.obter(tenant, () -> abrir.avulsa(maria)).comanda().id();

        lancarEFechar();

        assertThat(lancamentos()).singleElement()
                .satisfies(l -> assertThat(l.tipo()).isEqualTo(TipoDeLancamento.RECEITA_SERVICO));
    }

    // ------------------------------------------------------------------ imutabilidade

    @Test
    @DisplayName("a aplicação NÃO tem permissão de update no razão — o banco recusa")
    void update_em_lancamento_falha() throws java.sql.SQLException {
        // "Imutabilidade não é convenção, é permissão" (04-modelo-de-dados). Não é disciplina de
        // quem escreve o código: é recusado para quem escrever o código errado amanhã.
        cenario();
        lancarEFechar();

        try (var conexao = comoApp(tenant);
             var ps = conexao.prepareStatement("update lancamento set valor = 0")) {
            Throwable erro = catchThrowable(ps::executeUpdate);
            assertThat(erro).isNotNull();
            assertThat(erro.getMessage()).containsIgnoringCase("permission denied");
        }
    }

    @Test
    @DisplayName("a aplicação também não apaga")
    void delete_em_lancamento_falha() throws java.sql.SQLException {
        cenario();
        lancarEFechar();

        try (var conexao = comoApp(tenant);
             var ps = conexao.prepareStatement("delete from lancamento")) {
            Throwable erro = catchThrowable(ps::executeUpdate);
            assertThat(erro).isNotNull();
            assertThat(erro.getMessage()).containsIgnoringCase("permission denied");
        }
    }

    // ------------------------------------------------------------------ compensação

    @Test
    @DisplayName("reabrir anula as linhas com ESTORNO, sem apagar as originais")
    void reabrir_anula() {
        cenario();
        lancarEFechar();

        TenantContext.executar(tenant, () -> reabrir.executar(comanda, "item errado"));

        var linhas = lancamentos();
        assertThat(linhas).hasSize(4);
        assertThat(linhas).filteredOn(l -> l.tipo() == TipoDeLancamento.ESTORNO).hasSize(2);
        assertThat(linhas).filteredOn(l -> l.estornaId() != null).hasSize(2);
        assertThat(somaDe(linhas)).as("o razão volta a zero para esta comanda")
                .isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("fechar de novo depois de reabrir apura outra vez, sem dobrar")
    void refechar_apura_de_novo() {
        // É o risco que os eventos de RT-ATD-007 foram publicados para permitir compensar — e que
        // aqui é resolvido pela própria chamada, dentro da transação.
        cenario();
        lancarEFechar();
        TenantContext.executar(tenant, () -> reabrir.executar(comanda, "corrigir"));

        TenantContext.executar(tenant, () -> fechar.executar(comanda));

        var linhas = lancamentos();
        assertThat(linhas).hasSize(6);
        assertThat(somaDe(linhas)).as("uma receita e uma comissão, líquidas")
                .isEqualTo(Money.de("140"));
    }

    @Test
    @DisplayName("estornar anula as linhas e a comanda não volta")
    void estornar_anula() {
        cenario();
        lancarEFechar();

        TenantContext.executar(tenant, () -> estornar.executar(comanda, "cliente desistiu"));

        assertThat(somaDe(lancamentos())).isEqualTo(Money.ZERO);
        assertThat(lancamentos()).filteredOn(l -> l.descricao().contains("cliente desistiu"))
                .hasSize(2);
    }

    @Test
    @DisplayName("estornar duas vezes não anula duas vezes")
    void estorno_idempotente() throws java.sql.SQLException {
        // A idempotência vem do próprio razão: quem já foi estornado tem uma linha apontando para
        // si. Não há marca de "já anulado" para manter em dia.
        cenario();
        lancarEFechar();
        int primeira = TenantContext.obter(tenant,
                () -> razao.estornarOrigem("COMANDA", comanda, "primeira"));
        int segunda = TenantContext.obter(tenant,
                () -> razao.estornarOrigem("COMANDA", comanda, "segunda"));

        assertThat(primeira).isEqualTo(2);
        assertThat(segunda).as("nada mais a anular").isZero();
        assertThat(somaDe(lancamentos())).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("um salão não vê o razão do outro")
    void isolamento_entre_saloes() {
        cenario();
        lancarEFechar();
        UUID comandaDoA = comanda;
        UUID salaoB = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Outro " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        assertThat(TenantContext.obter(salaoB, () -> razao.daOrigem("COMANDA", comandaDoA)))
                .isEmpty();
    }

    private Money somaDe(List<Lancamento> linhas) {
        return linhas.stream().map(Lancamento::valor).reduce(Money.ZERO, Money::somar);
    }

    /** Um shampoo de R$ 45,00 com estoque — sem lote, o fechamento recusaria (RN-ATD-020). */
    private UUID cadastrarProdutoComEstoque() {
        UUID produto = cadastrarProduto.executar(new DadosDoProduto(
                "Shampoo", null, FinalidadeDoProduto.VENDA, UnidadeDeMedida.UN,
                Money.de("45"))).id();
        entradaDeLote.executar(produto, new DadosDaEntrada(
                new BigDecimal("10"), Money.de("20"), null, null, null));
        return produto;
    }
}
