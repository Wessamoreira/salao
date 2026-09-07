package br.com.salao.financeiro.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.atendimento.internal.application.AbrirComandaUseCase;
import br.com.salao.atendimento.internal.application.AdicionarItemDeServicoUseCase;
import br.com.salao.atendimento.internal.application.FecharComandaUseCase;
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
import br.com.salao.financeiro.api.ExtratoDoProfissional;
import br.com.salao.financeiro.api.FormaDePagamento;
import br.com.salao.financeiro.api.FormaDoVale;
import br.com.salao.financeiro.api.TipoDeLancamento;
import br.com.salao.financeiro.api.Vale;
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

/** RT-FIN-005 — vale (adiantamento). */
@WithMockUser(authorities = { Permissao.FINANCEIRO_ADIANTAR, Permissao.FINANCEIRO_LER_TODOS,
        Permissao.COMANDA_ABRIR, Permissao.COMANDA_FECHAR, Permissao.CLIENTE_ESCREVER,
        Permissao.CONFIG_GERENCIAR, Permissao.USUARIO_GERENCIAR })
class ValeIT extends AbstractPostgresIT {

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarClienteUseCase cadastrarCliente;
    @Autowired private CadastrarProfissionalUseCase cadastrarProfissional;
    @Autowired private CadastrarServicoUseCase cadastrarServico;
    @Autowired private DefinirPrecoUseCase definirPreco;
    @Autowired private AbrirComandaUseCase abrir;
    @Autowired private AdicionarItemDeServicoUseCase adicionarServico;
    @Autowired private RegistrarPagamentoUseCase pagar;
    @Autowired private FecharComandaUseCase fechar;
    @Autowired private RegistrarValeUseCase registrarVale;
    @Autowired private CancelarValeUseCase cancelarVale;
    @Autowired private ListarValesUseCase listarVales;
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

    /** Uma coloração de R$ 200 fechada e paga: R$ 60 de comissão para a Ana. */
    private void atender() {
        UUID comanda = TenantContext.obter(tenant, () -> abrir.avulsa(maria)).comanda().id();
        TenantContext.executar(tenant,
                () -> adicionarServico.executar(comanda, coloracao, ana, null, null));
        TenantContext.executar(tenant, () -> pagar.executar(comanda,
                DadosDoPagamento.de(FormaDePagamento.PIX, Money.de("200"))));
        TenantContext.executar(tenant, () -> fechar.executar(comanda));
    }

    private Vale vale(String valor, String motivo) {
        return TenantContext.obter(tenant, () -> registrarVale.executar(new NovoVale(
                ana, Money.de(valor), FormaDoVale.DINHEIRO, motivo, null)));
    }

    private ExtratoDoProfissional doMes() {
        LocalDate hoje = LocalDate.now();
        return TenantContext.obter(tenant, () -> extrato.executar(ana,
                hoje.withDayOfMonth(1), hoje.withDayOfMonth(hoje.lengthOfMonth())));
    }

    private Money totalDe(ExtratoDoProfissional e, TipoDeLancamento tipo) {
        return e.totais().stream()
                .filter(t -> t.tipo() == tipo)
                .map(ExtratoDoProfissional.TotalPorTipo::valor)
                .findFirst()
                .orElse(Money.ZERO);
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ o efeito no extrato

    @Test
    @DisplayName("o vale reduz o que a pessoa tem a receber")
    void vale_reduz_o_a_receber() {
        // No razão ele entra POSITIVO — devolve ao salão parte do que ele devia. O extrato inverte
        // uma vez (RN-FIN-011), e é lá que aparece com o sinal de menos que a pessoa espera ver.
        cenario();
        atender();

        vale("50", "adiantamento da semana");

        var meu = doMes();
        assertThat(meu.aReceber()).isEqualTo(Money.de("10"));
        assertThat(meu.linhas()).hasSize(2);
        assertThat(meu.linhas().get(1).tipo()).isEqualTo(TipoDeLancamento.VALE);
        assertThat(meu.linhas().get(1).valor()).isEqualTo(Money.de("-50"));
    }

    @Test
    @DisplayName("o extrato abre o total por tipo: R$ 60 ganhos, R$ 50 adiantados")
    void totais_por_tipo() {
        // Sem a abertura, quem ganhou 600 e adiantou 550 lê "50" e conclui que trabalhou por 50 —
        // a mesma conversa que o extrato existe para encerrar, voltando pela outra porta.
        cenario();
        atender();
        vale("50", null);

        var meu = doMes();

        assertThat(totalDe(meu, TipoDeLancamento.COMISSAO)).isEqualTo(Money.de("60"));
        assertThat(totalDe(meu, TipoDeLancamento.VALE)).isEqualTo(Money.de("-50"));
    }

    @Test
    @DisplayName("a linha do vale se explica sozinha: a forma e o motivo estão nela")
    void linha_se_explica() {
        // A linha do razão é a única coisa que sobrevive a tudo, e "o que foi esse vale?" precisa
        // ter resposta sem abrir mais nada.
        cenario();
        vale("50", "farmácia");

        var linha = doMes().linhas().get(0);

        assertThat(linha.descricao()).contains("dinheiro").contains("farmácia");
        assertThat(linha.origemTipo()).isEqualTo("VALE");
    }

    @Test
    @DisplayName("o vale sem motivo não inventa um")
    void sem_motivo() {
        cenario();
        vale("50", null);

        assertThat(doMes().linhas().get(0).descricao()).isEqualTo("Vale em dinheiro");
    }

    @Test
    @DisplayName("adiantar mais do que a pessoa ganhou deixa o saldo negativo, e é permitido")
    void vale_maior_que_o_saldo() {
        // Adiantar mais do que já se ganhou é o que "adiantamento" significa. Recusar seria
        // inventar uma regra que o salão não tem.
        cenario();
        atender();

        vale("100", "emergência");

        assertThat(doMes().aReceber()).as("a pessoa passa a dever ao salão")
                .isEqualTo(Money.de("-40"));
    }

    // ------------------------------------------------------------------ cancelamento

    @Test
    @DisplayName("cancelar devolve o saldo, e o vale continua visível marcado")
    void cancelar_devolve_o_saldo() {
        cenario();
        atender();
        Vale registrado = vale("50", "engano");

        Vale depois = TenantContext.obter(tenant,
                () -> cancelarVale.executar(registrado.id(), "lançado na pessoa errada"));

        assertThat(depois.cancelado()).isTrue();
        assertThat(doMes().aReceber()).isEqualTo(Money.de("60"));
        assertThat(doMes().linhas()).as("nada é escondido: comissão, vale, estorno").hasSize(3);
    }

    @Test
    @DisplayName("cancelar duas vezes não estorna duas vezes")
    void cancelar_e_idempotente() {
        // A idempotência vem do próprio razão (RN-FIN-010): quem já foi estornado tem uma linha
        // apontando para si.
        cenario();
        atender();
        Vale registrado = vale("50", "engano");

        TenantContext.executar(tenant, () -> cancelarVale.executar(registrado.id(), "primeiro"));
        TenantContext.executar(tenant, () -> cancelarVale.executar(registrado.id(), "de novo"));

        assertThat(doMes().linhas()).hasSize(3);
        assertThat(doMes().aReceber()).isEqualTo(Money.de("60"));
    }

    @Test
    @DisplayName("cancelar exige motivo")
    void cancelar_exige_motivo() {
        cenario();
        Vale registrado = vale("50", null);

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> cancelarVale.executar(registrado.id(), "  ")));

        assertThat(codigoDe(erro)).isEqualTo("ER-FIN-DADOS_INVALIDOS");
    }

    @Test
    @DisplayName("cancelar um vale que não existe é 404, não silêncio")
    void cancelar_inexistente() {
        cenario();

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> cancelarVale.executar(UUID.randomUUID(), "qualquer")));

        assertThat(codigoDe(erro)).isEqualTo("ER-FIN-VALE_NAO_ENCONTRADO");
    }

    // ------------------------------------------------------------------ recusas

    @Test
    @DisplayName("vale de zero é recusado com uma frase, não com violação de constraint")
    void valor_precisa_ser_positivo() {
        cenario();

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> registrarVale.executar(new NovoVale(ana, Money.ZERO, FormaDoVale.PIX,
                        null, null))));

        assertThat(codigoDe(erro)).isEqualTo("ER-FIN-DADOS_INVALIDOS");
    }

    @Test
    @DisplayName("vale sem forma é recusado: falta saber de onde o dinheiro saiu")
    void forma_e_obrigatoria() {
        cenario();

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> registrarVale.executar(new NovoVale(ana, Money.de("50"), null, null,
                        null))));

        assertThat(codigoDe(erro)).isEqualTo("ER-FIN-DADOS_INVALIDOS");
    }

    @Test
    @DisplayName("um salão não adianta para o profissional do outro")
    void isolamento_entre_saloes() {
        cenario();
        UUID anaDoA = ana;
        UUID salaoB = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Outro " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        Throwable erro = catchThrowable(() -> TenantContext.executar(salaoB,
                () -> registrarVale.executar(new NovoVale(anaDoA, Money.de("50"),
                        FormaDoVale.PIX, null, null))));

        assertThat(codigoDe(erro)).isEqualTo("ER-FIN-DADOS_INVALIDOS");
    }

    @Test
    @DisplayName("sem financeiro:advance, adiantar é recusado")
    @WithMockUser(authorities = Permissao.FINANCEIRO_LER_TODOS)
    void sem_permissao_nao_adianta() {
        // Ler o financeiro e tirar dinheiro do caixa são atos diferentes.
        UUID salao = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        assertThatThrownBy(() -> TenantContext.executar(salao,
                () -> registrarVale.executar(new NovoVale(UUID.randomUUID(), Money.de("50"),
                        FormaDoVale.PIX, null, null))))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ------------------------------------------------------------------ a lista

    @Test
    @DisplayName("a lista traz o cancelado marcado, e não o esconde")
    void lista_marca_cancelado() {
        // Quem confere o caixa precisa ver que a saída existiu e foi desfeita.
        cenario();
        Vale valido = vale("50", "ok");
        Vale desfeito = vale("30", "engano");
        TenantContext.executar(tenant, () -> cancelarVale.executar(desfeito.id(), "engano"));

        List<Vale> lista = TenantContext.obter(tenant, () -> listarVales.executar(ana,
                LocalDate.now().withDayOfMonth(1), LocalDate.now()));

        assertThat(lista).hasSize(2);
        assertThat(lista).filteredOn(v -> v.id().equals(valido.id()))
                .singleElement().extracting(Vale::cancelado).isEqualTo(false);
        assertThat(lista).filteredOn(v -> v.id().equals(desfeito.id()))
                .singleElement().extracting(Vale::cancelado).isEqualTo(true);
    }

    @Test
    @DisplayName("sem profissional, a lista traz os vales do salão inteiro")
    void lista_do_salao() {
        cenario();
        UUID bia = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional("Bia Nunes", null, "52998224725",
                        TipoDeVinculo.EMPREGADO, null, null, new BigDecimal("50"),
                        null, null, null))).id();
        vale("50", null);
        TenantContext.executar(tenant, () -> registrarVale.executar(new NovoVale(bia,
                Money.de("20"), FormaDoVale.PIX, null, null)));

        List<Vale> todos = TenantContext.obter(tenant, () -> listarVales.executar(null,
                LocalDate.now().withDayOfMonth(1), LocalDate.now()));
        List<Vale> soDaAna = TenantContext.obter(tenant, () -> listarVales.executar(ana,
                LocalDate.now().withDayOfMonth(1), LocalDate.now()));

        assertThat(todos).hasSize(2);
        assertThat(soDaAna).singleElement().extracting(Vale::valor).isEqualTo(Money.de("50"));
    }

    @Test
    @DisplayName("período invertido é recusado, como no extrato")
    void periodo_invertido() {
        cenario();

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> listarVales.executar(null, LocalDate.now(),
                        LocalDate.now().minusDays(1))));

        assertThat(codigoDe(erro)).isEqualTo("ER-FIN-DADOS_INVALIDOS");
    }

    @Test
    @DisplayName("um salão não lista o vale do outro")
    void lista_isolada() {
        cenario();
        vale("50", null);
        UUID salaoB = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Outro " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        List<Vale> doOutro = TenantContext.obter(salaoB, () -> listarVales.executar(null,
                LocalDate.now().withDayOfMonth(1), LocalDate.now()));

        assertThat(doOutro).isEmpty();
    }

    // ------------------------------------------------------------------ imutabilidade

    @Test
    @DisplayName("update em vale é recusado pelo BANCO, não pelo código")
    void update_em_vale_falha() throws java.sql.SQLException {
        cenario();
        vale("50", null);

        try (var conexao = comoApp(tenant);
             var ps = conexao.prepareStatement("update vale set valor = 1")) {
            Throwable erro = catchThrowable(ps::executeUpdate);
            assertThat(erro).isNotNull();
            assertThat(erro.getMessage()).containsIgnoringCase("permission denied");
        }
    }

    @Test
    @DisplayName("vale de zero é recusado pelo BANCO, e não só pelo caso de uso")
    void banco_recusa_valor_nao_positivo() throws java.sql.SQLException {
        // O caso de uso recusa com uma frase; o banco recusa para quem escrever o código errado
        // amanhã. As duas coisas, e não uma delas — "o banco garante; a aplicação valida por
        // educação".
        cenario();

        try (var conexao = comoApp(tenant);
             var ps = conexao.prepareStatement(
                     "insert into vale (estabelecimento_id, profissional_id, valor, forma,"
                             + " data_competencia, criado_em)"
                             + " values (?, ?, 0, 'PIX', current_date, now())")) {
            ps.setObject(1, tenant);
            ps.setObject(2, ana);
            Throwable erro = catchThrowable(ps::executeUpdate);
            assertThat(erro).isNotNull();
            assertThat(erro.getMessage()).containsIgnoringCase("vale_valor_check");
        }
    }

    @Test
    @DisplayName("delete em vale é recusado pelo BANCO")
    void delete_em_vale_falha() throws java.sql.SQLException {
        cenario();
        vale("50", null);

        try (var conexao = comoApp(tenant);
             var ps = conexao.prepareStatement("delete from vale")) {
            Throwable erro = catchThrowable(ps::executeUpdate);
            assertThat(erro).isNotNull();
            assertThat(erro.getMessage()).containsIgnoringCase("permission denied");
        }
    }

    // ------------------------------------------------------------------ idempotência

    @Test
    @DisplayName("a mesma Idempotency-Key não entrega o dinheiro duas vezes")
    void chave_nao_entrega_duas_vezes() {
        // Duplo clique no balcão com dinheiro saindo é o erro que ninguém percebe até o fechamento.
        cenario();
        atender();
        String chave = UUID.randomUUID().toString();
        NovoVale pedido = new NovoVale(ana, Money.de("50"), FormaDoVale.DINHEIRO, "semana", chave);

        Vale primeiro = TenantContext.obter(tenant, () -> registrarVale.executar(pedido));
        Vale segundo = TenantContext.obter(tenant, () -> registrarVale.executar(pedido));

        assertThat(segundo.id()).isEqualTo(primeiro.id());
        assertThat(doMes().aReceber()).as("um vale só saiu do caixa").isEqualTo(Money.de("10"));
    }
}
