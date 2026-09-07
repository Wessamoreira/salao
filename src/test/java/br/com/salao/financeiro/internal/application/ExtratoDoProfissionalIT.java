package br.com.salao.financeiro.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
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
import br.com.salao.financeiro.api.DadosDoPagamento;
import br.com.salao.financeiro.api.FormaDePagamento;
import br.com.salao.financeiro.api.TipoDeLancamento;
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
import org.springframework.security.test.context.support.WithMockUser;

/** RT-FIN-004 — o extrato do profissional, item a item. */
@WithMockUser(authorities = { Permissao.FINANCEIRO_LER_TODOS, Permissao.COMANDA_ABRIR,
        Permissao.COMANDA_FECHAR, Permissao.COMANDA_REABRIR, Permissao.CLIENTE_ESCREVER,
        Permissao.CONFIG_GERENCIAR, Permissao.USUARIO_GERENCIAR })
class ExtratoDoProfissionalIT extends AbstractPostgresIT {

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarClienteUseCase cadastrarCliente;
    @Autowired private CadastrarProfissionalUseCase cadastrarProfissional;
    @Autowired private CadastrarServicoUseCase cadastrarServico;
    @Autowired private DefinirPrecoUseCase definirPreco;
    @Autowired private AbrirComandaUseCase abrir;
    @Autowired private AdicionarItemDeServicoUseCase adicionarServico;
    @Autowired private RegistrarPagamentoUseCase pagar;
    @Autowired private FecharComandaUseCase fechar;
    @Autowired private ReabrirComandaUseCase reabrir;
    @Autowired private ConsultarExtratoUseCase extrato;

    private UUID tenant;
    private UUID ana;
    private UUID coloracao;
    private UUID maria;

    private void cenario() {
        tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        maria = TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                new DadosDoCliente("Maria Silva", null, null, null, null)));
        ana = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional("Ana Ribeiro", "Ana", "11144477735",
                        TipoDeVinculo.EMPREGADO, null, null, new BigDecimal("30"),
                        null, null, null))).id();
        coloracao = TenantContext.obter(tenant, () -> cadastrarServico.executar("Coloração", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 90)))).id();
        TenantContext.executar(tenant, () -> definirPreco.executar(coloracao, Money.de("200"),
                LocalDate.now().minusDays(1)));
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

    private br.com.salao.financeiro.api.ExtratoDoProfissional doMes() {
        LocalDate hoje = LocalDate.now();
        return TenantContext.obter(tenant, () -> extrato.executar(ana,
                hoje.withDayOfMonth(1), hoje.withDayOfMonth(hoje.lengthOfMonth())));
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ o extrato

    @Test
    @DisplayName("a comissão aparece POSITIVA no extrato, invertida uma vez")
    void comissao_positiva() {
        // No razão ela é negativa: sai do salão. Na conta da pessoa ela entra. A inversão acontece
        // aqui, uma vez, para ninguém precisar lembrar disso ao ler a tela.
        cenario();
        atender();

        var meu = doMes();

        assertThat(meu.nome()).isEqualTo("Ana");
        assertThat(meu.aReceber()).isEqualTo(Money.de("60"));
        assertThat(meu.linhas()).singleElement().satisfies(l -> {
            assertThat(l.tipo()).isEqualTo(TipoDeLancamento.COMISSAO);
            assertThat(l.valor()).isEqualTo(Money.de("60"));
        });
    }

    @Test
    @DisplayName("cada linha diz de qual comanda veio, com o serviço e o percentual")
    void linha_explica_de_onde_veio() {
        // "Recebi menos esse mês" é a conversa que este extrato existe para encerrar — e um total
        // sozinho não a encerra, obriga a pessoa a acreditar.
        cenario();
        UUID comanda = atender();

        var linha = doMes().linhas().get(0);

        assertThat(linha.comandaId()).isEqualTo(comanda);
        assertThat(linha.descricao()).contains("Coloração").contains("30")
                .contains("PROFISSIONAL");
    }

    @Test
    @DisplayName("dois atendimentos somam")
    void dois_atendimentos() {
        cenario();
        atender();
        atender();

        assertThat(doMes().aReceber()).isEqualTo(Money.de("120"));
        assertThat(doMes().linhas()).hasSize(2);
    }

    @Test
    @DisplayName("o estorno aparece como LINHA, não como subtração invisível")
    void estorno_aparece() {
        // Esconder as linhas de estorno faria o total não bater com nada que a pessoa consiga
        // conferir contra as comandas dela.
        cenario();
        UUID comanda = atender();

        TenantContext.executar(tenant, () -> reabrir.executar(comanda, "item errado"));

        var meu = doMes();
        assertThat(meu.linhas()).hasSize(2);
        assertThat(meu.linhas()).extracting(l -> l.tipo().name())
                .containsExactly("COMISSAO", "ESTORNO");
        assertThat(meu.linhas().get(1).valor())
                .as("o estorno entra negativo na conta da pessoa").isEqualTo(Money.de("-60"));
        assertThat(meu.aReceber()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("reaberta e refechada: três linhas, e o total é de UM atendimento")
    void reaberta_e_refechada() {
        // O razão acumula: comissão, estorno, comissão. Está certo, e um extrato que só listasse
        // ficaria confuso — por isso ele SOMA, e mostra as três para quem quiser conferir.
        cenario();
        UUID comanda = atender();
        TenantContext.executar(tenant, () -> reabrir.executar(comanda, "corrigir"));

        TenantContext.executar(tenant, () -> fechar.executar(comanda));

        var meu = doMes();
        assertThat(meu.linhas()).as("nada é escondido").hasSize(3);
        assertThat(meu.linhas()).extracting(l -> l.tipo().name())
                .containsExactly("COMISSAO", "ESTORNO", "COMISSAO");
        assertThat(meu.aReceber()).as("o líquido é de um atendimento só")
                .isEqualTo(Money.de("60"));
    }

    @Test
    @DisplayName("fora do período não entra")
    void fora_do_periodo() {
        cenario();
        atender();

        var mesPassado = TenantContext.obter(tenant, () -> extrato.executar(ana,
                LocalDate.now().minusMonths(2).withDayOfMonth(1),
                LocalDate.now().minusMonths(2).withDayOfMonth(28)));

        assertThat(mesPassado.linhas()).isEmpty();
        assertThat(mesPassado.aReceber()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("o extrato de uma pessoa não traz o da outra")
    void extrato_e_de_uma_pessoa_so() {
        cenario();
        UUID bia = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional("Bia Nunes", null, "52998224725",
                        TipoDeVinculo.EMPREGADO, null, null, new BigDecimal("50"),
                        null, null, null))).id();
        atender();

        var daBia = TenantContext.obter(tenant, () -> extrato.executar(bia,
                LocalDate.now().withDayOfMonth(1), LocalDate.now()));

        assertThat(daBia.linhas()).isEmpty();
        assertThat(daBia.aReceber()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("período invertido é recusado")
    void periodo_invertido() {
        cenario();

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> extrato.executar(ana, LocalDate.now(), LocalDate.now().minusDays(1))));

        assertThat(codigoDe(erro)).isEqualTo("ER-FIN-DADOS_INVALIDOS");
    }

    @Test
    @DisplayName("um salão não vê o extrato do outro")
    void isolamento_entre_saloes() {
        cenario();
        atender();
        UUID anaDoA = ana;
        UUID salaoB = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Outro " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        Throwable erro = catchThrowable(() -> TenantContext.executar(salaoB,
                () -> extrato.executar(anaDoA, LocalDate.now().withDayOfMonth(1),
                        LocalDate.now())));

        assertThat(codigoDe(erro)).as("nem o profissional existe para o outro salão")
                .isEqualTo("ER-FIN-DADOS_INVALIDOS");
    }
}
