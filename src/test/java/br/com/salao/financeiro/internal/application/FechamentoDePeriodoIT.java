package br.com.salao.financeiro.internal.application;

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
import br.com.salao.financeiro.api.DadosDoPagamento;
import br.com.salao.financeiro.api.ExtratoDoProfissional;
import br.com.salao.financeiro.api.FechamentoDoProfissional;
import br.com.salao.financeiro.api.FormaDePagamento;
import br.com.salao.financeiro.api.FormaDoVale;
import br.com.salao.financeiro.api.PreviaDoFechamento;
import br.com.salao.financeiro.api.TipoDeLancamento;
import br.com.salao.financeiro.internal.infra.LancamentosJdbc;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-FIN-006 — fechamento de período e repasse. */
@WithMockUser(authorities = { Permissao.FINANCEIRO_FECHAR, Permissao.FINANCEIRO_LER_TODOS,
        Permissao.FINANCEIRO_ADIANTAR, Permissao.COMANDA_ABRIR, Permissao.COMANDA_FECHAR,
        Permissao.COMANDA_REABRIR, Permissao.COMANDA_DESCONTO, Permissao.CLIENTE_ESCREVER,
        Permissao.CONFIG_GERENCIAR, Permissao.USUARIO_GERENCIAR })
class FechamentoDePeriodoIT extends AbstractPostgresIT {

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
    @Autowired private RegistrarValeUseCase registrarVale;
    @Autowired private ListarValesUseCase listarVales;
    @Autowired private PreverFechamentoUseCase prever;
    @Autowired private FecharPeriodoUseCase fecharPeriodo;
    @Autowired private ListarFechamentosUseCase listarFechamentos;
    @Autowired private ConsultarExtratoUseCase extrato;
    @Autowired private LancamentosJdbc lancamentos;
    @Autowired private Relogio relogio;

    private UUID tenant;
    private UUID ana;
    private UUID bia;
    private UUID coloracao;
    private UUID corte;
    private UUID maria;

    private LocalDate hoje;

    private void cenario() {
        tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        hoje = TenantContext.obter(tenant, () -> relogio.hojeEm(
                java.time.ZoneId.of("America/Sao_Paulo")));
        maria = TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                new DadosDoCliente("Maria Silva", null, null, null, null)));
        ana = profissional("Ana Ribeiro", "Ana", "11144477735", "30");
        bia = profissional("Bia Nunes", "Bia", "52998224725", "50");
        coloracao = servico("Coloração", "200");
        corte = servico("Corte", "99.90");
    }

    private UUID profissional(String nome, String apelido, String documento, String percentual) {
        return TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional(nome, apelido, documento, TipoDeVinculo.EMPREGADO,
                        null, null, new BigDecimal(percentual), null, null, null))).id();
    }

    private UUID servico(String nome, String preco) {
        UUID id = TenantContext.obter(tenant, () -> cadastrarServico.executar(nome, null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 60)))).id();
        TenantContext.executar(tenant, () -> definirPreco.executar(id, Money.de(preco),
                hoje.minusDays(60)));
        return id;
    }

    /** Um atendimento fechado e pago. Devolve o id da comanda. */
    private UUID atender(UUID servicoId, UUID profissionalId, String pago) {
        UUID comanda = TenantContext.obter(tenant, () -> abrir.avulsa(maria)).comanda().id();
        TenantContext.executar(tenant,
                () -> adicionarServico.executar(comanda, servicoId, profissionalId, null, null));
        TenantContext.executar(tenant, () -> pagar.executar(comanda,
                DadosDoPagamento.de(FormaDePagamento.PIX, Money.de(pago))));
        TenantContext.executar(tenant, () -> fechar.executar(comanda));
        return comanda;
    }

    private void vale(UUID profissionalId, String valor, FormaDoVale forma) {
        TenantContext.executar(tenant, () -> registrarVale.executar(
                new NovoVale(profissionalId, Money.de(valor), forma, "adiantamento", null)));
    }

    /**
     * Escreve uma comissão diretamente no razão, numa competência escolhida.
     *
     * <p>Comanda fechada nasce sempre com a competência de hoje (RN-FIN-010), e não há relógio
     * ajustável nos testes — sem isto, dois períodos consecutivos e ambos no passado seriam
     * impossíveis de montar. O que está sob teste aqui é o fechamento, e o contrato dele é com o
     * razão.
     */
    private void comissaoEm(UUID profissionalId, String valor, LocalDate competencia) {
        TenantContext.executar(tenant, () -> lancamentos.inserir(tenant, competencia,
                TipoDeLancamento.COMISSAO, "COMANDA", UUID.randomUUID(), "PROFISSIONAL",
                profissionalId, Money.de(valor).negado(), "Serviço antigo", null,
                relogio.agora(), null));
    }

    private void valeEm(UUID profissionalId, String valor, LocalDate competencia) {
        TenantContext.executar(tenant, () -> lancamentos.inserir(tenant, competencia,
                TipoDeLancamento.VALE, "VALE", UUID.randomUUID(), "PROFISSIONAL",
                profissionalId, Money.de(valor), "Vale antigo", null, relogio.agora(), null));
    }

    private List<FechamentoDoProfissional> fecharDe(LocalDate de, LocalDate ate) {
        return TenantContext.obter(tenant, () -> {
            PreviaDoFechamento previa = prever.executar(de, ate);
            return fecharPeriodo.executar(de, ate, previa.versao());
        });
    }

    private FechamentoDoProfissional doProfissional(List<FechamentoDoProfissional> feitos,
                                                    UUID profissionalId) {
        return feitos.stream().filter(f -> f.profissionalId().equals(profissionalId))
                .findFirst().orElseThrow();
    }

    private ExtratoDoProfissional extratoDe(UUID profissionalId, LocalDate de, LocalDate ate) {
        return TenantContext.obter(tenant, () -> extrato.executar(profissionalId, de, ate));
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ o repasse

    @Test
    @DisplayName("o repasse zera a conta da pessoa")
    void repasse_zera_a_conta() {
        cenario();
        atender(coloracao, ana, "200");

        var feitos = fecharDe(hoje.minusDays(5), hoje);

        assertThat(doProfissional(feitos, ana).valorRepassado()).isEqualTo(Money.de("60"));
        assertThat(extratoDe(ana, hoje.minusDays(5), hoje).saldoFinal())
                .as("depois de pago, não sobra nada a receber").isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("o vale é descontado do repasse")
    void vale_e_descontado() {
        cenario();
        atender(coloracao, ana, "200");
        vale(ana, "50", FormaDoVale.DINHEIRO);

        var feitos = fecharDe(hoje.minusDays(5), hoje);

        assertThat(doProfissional(feitos, ana).valorRepassado()).isEqualTo(Money.de("10"));
    }

    @Test
    @DisplayName("a linha de REPASSE tem a competência do FIM do período, não a de hoje")
    void repasse_pertence_ao_periodo() {
        // Um repasse de março datado de 5 de abril deixaria o extrato de março mostrando dinheiro
        // a receber que já foi pago — e a pessoa cobraria de novo, com razão.
        cenario();
        comissaoEm(ana, "100", hoje.minusDays(20));

        fecharDe(hoje.minusDays(25), hoje.minusDays(15));

        var doPeriodo = extratoDe(ana, hoje.minusDays(25), hoje.minusDays(15));
        assertThat(doPeriodo.linhas()).extracting(l -> l.tipo().name())
                .containsExactly("COMISSAO", "REPASSE");
        assertThat(doPeriodo.linhas().get(1).data()).isEqualTo(hoje.minusDays(15));
        assertThat(doPeriodo.saldoFinal()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("quem não tem posição nenhuma não vira contracheque")
    void sem_posicao_nao_entra() {
        cenario();
        atender(coloracao, ana, "200");

        var feitos = fecharDe(hoje.minusDays(5), hoje);

        assertThat(feitos).singleElement()
                .extracting(FechamentoDoProfissional::profissionalId).isEqualTo(ana);
    }

    // ------------------------------------------------------------------ a dívida atravessa

    @Test
    @DisplayName("quem adiantou demais não recebe, e a dívida atravessa para o período seguinte")
    void divida_atravessa() {
        // É o buraco que um cálculo por período teria: a dívida sumiria da vista no dia 1º, e o
        // salão perderia o dinheiro sem nada acusar.
        cenario();
        comissaoEm(ana, "60", hoje.minusDays(20));
        valeEm(ana, "100", hoje.minusDays(20));

        var primeiro = fecharDe(hoje.minusDays(25), hoje.minusDays(15));

        assertThat(doProfissional(primeiro, ana).valorRepassado()).isEqualTo(Money.ZERO);
        assertThat(doProfissional(primeiro, ana).saldoATransportar())
                .as("ela terminou o período devendo 40").isEqualTo(Money.de("-40"));

        comissaoEm(ana, "60", hoje.minusDays(10));
        var segundo = fecharDe(hoje.minusDays(14), hoje.minusDays(5));

        assertThat(doProfissional(segundo, ana).saldoAnterior()).isEqualTo(Money.de("-40"));
        assertThat(doProfissional(segundo, ana).valorRepassado())
                .as("ganhou 60, devia 40, recebe 20").isEqualTo(Money.de("20"));
        assertThat(doProfissional(segundo, ana).saldoATransportar()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("o extrato mostra o saldo anterior, senão promete mais do que o fechamento paga")
    void extrato_mostra_saldo_anterior() {
        cenario();
        comissaoEm(ana, "60", hoje.minusDays(20));
        valeEm(ana, "100", hoje.minusDays(20));
        fecharDe(hoje.minusDays(25), hoje.minusDays(15));
        comissaoEm(ana, "60", hoje.minusDays(10));

        var doSegundo = extratoDe(ana, hoje.minusDays(14), hoje.minusDays(5));

        assertThat(doSegundo.aReceber()).as("o período sozinho").isEqualTo(Money.de("60"));
        assertThat(doSegundo.saldoAnterior()).isEqualTo(Money.de("-40"));
        assertThat(doSegundo.saldoFinal()).as("o que ela realmente vai receber")
                .isEqualTo(Money.de("20"));
    }

    // ------------------------------------------------------------------ a prévia

    @Test
    @DisplayName("a prévia bate, centavo a centavo, com o que o fechamento faz")
    void previa_bate_com_o_fechamento() {
        // Uma prévia que divirja do fechamento aparece depois do clique, na única tela do sistema
        // em que ninguém aceita ser surpreendido.
        cenario();
        atender(coloracao, ana, "200");
        atender(corte, bia, "99.90");
        vale(ana, "50", FormaDoVale.DINHEIRO);

        PreviaDoFechamento previa = TenantContext.obter(tenant,
                () -> prever.executar(hoje.minusDays(5), hoje));
        var feitos = fecharDe(hoje.minusDays(5), hoje);

        Money somaDosRepasses = feitos.stream()
                .map(FechamentoDoProfissional::valorRepassado)
                .reduce(Money.ZERO, Money::somar);
        assertThat(previa.totalARepassar()).isEqualTo(somaDosRepasses);
        assertThat(previa.totalARepassar()).as("60 - 50 da Ana, mais 49,95 da Bia")
                .isEqualTo(Money.de("59.95"));
        assertThat(previa.quantosRecebem()).isEqualTo(2);
    }

    @Test
    @DisplayName("a prévia não grava: chamá-la duas vezes não fecha nada")
    void previa_nao_grava() {
        cenario();
        atender(coloracao, ana, "200");

        TenantContext.executar(tenant, () -> prever.executar(hoje.minusDays(5), hoje));
        TenantContext.executar(tenant, () -> prever.executar(hoje.minusDays(5), hoje));

        assertThat(fecharDe(hoje.minusDays(5), hoje)).hasSize(1);
    }

    @Test
    @DisplayName("a versão da prévia é a mesma em duas leituras do mesmo dado")
    void versao_da_previa_e_estavel() {
        // Que a ordem das posições seja estável é `ContasDoPeriodoTest` quem garante — lá dá
        // para forçar o banco a devolver ao contrário, e aqui não. Este teste cobre o que só
        // o banco de verdade mostra: duas consultas separadas, mesmo dado, mesma versão.
        cenario();
        comissaoEm(ana, "60", hoje.minusDays(2));
        comissaoEm(bia, "30", hoje.minusDays(2));
        LocalDate de = hoje.minusDays(5);

        PreviaDoFechamento primeira = TenantContext.obter(tenant,
                () -> prever.executar(de, hoje));
        PreviaDoFechamento segunda = TenantContext.obter(tenant,
                () -> prever.executar(de, hoje));

        assertThat(segunda.versao())
                .as("mesmo dado, duas leituras: a versão não pode mudar")
                .isEqualTo(primeira.versao());
        assertThat(primeira.linhas()).hasSize(2);
    }

    @Test
    @DisplayName("se os números mudam depois da prévia, o fechamento exige nova conferência")
    void previa_desatualizada() {
        cenario();
        LocalDate de = hoje.minusDays(5);
        PreviaDoFechamento previa = TenantContext.obter(tenant, () -> prever.executar(de, hoje));
        comissaoEm(ana, "60", hoje.minusDays(2));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> fecharPeriodo.executar(de, hoje, previa.versao())));

        assertThat(codigoDe(erro)).isEqualTo("ER-FIN-PREVIA_DESATUALIZADA");
        assertThat(extratoDe(ana, de, hoje).saldoFinal()).isEqualTo(Money.de("60"));
    }

    @Test
    @DisplayName("a prévia recusa o que o fechamento recusaria")
    void previa_recusa_o_mesmo() {
        cenario();

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> prever.executar(hoje.minusDays(5), hoje.plusDays(1))));

        assertThat(codigoDe(erro)).isEqualTo("ER-FIN-DADOS_INVALIDOS");
    }

    // ------------------------------------------------------------------ recusas

    @Test
    @DisplayName("fechar duas vezes o mesmo período é recusado")
    void nao_fecha_duas_vezes() {
        cenario();
        atender(coloracao, ana, "200");
        fecharDe(hoje.minusDays(5), hoje);

        Throwable erro = catchThrowable(() -> fecharDe(hoje.minusDays(5), hoje));

        assertThat(codigoDe(erro)).isEqualTo("ER-FIN-PERIODO_JA_FECHADO");
    }

    @Test
    @DisplayName("não se fecha para trás: março depois de abril pagaria março de novo")
    void nao_fecha_para_tras() {
        cenario();
        comissaoEm(ana, "60", hoje.minusDays(10));
        fecharDe(hoje.minusDays(14), hoje.minusDays(5));

        Throwable erro = catchThrowable(
                () -> fecharDe(hoje.minusDays(30), hoje.minusDays(20)));

        assertThat(codigoDe(erro)).isEqualTo("ER-FIN-PERIODO_JA_FECHADO");
    }

    @Test
    @DisplayName("não se fecha um período que ainda não terminou")
    void nao_fecha_o_futuro() {
        cenario();

        Throwable erro = catchThrowable(() -> fecharDe(hoje, hoje.plusDays(10)));

        assertThat(codigoDe(erro)).isEqualTo("ER-FIN-DADOS_INVALIDOS");
    }

    @Test
    @DisplayName("período invertido é recusado")
    void periodo_invertido() {
        cenario();

        Throwable erro = catchThrowable(
                () -> fecharDe(hoje, hoje.minusDays(5)));

        assertThat(codigoDe(erro)).isEqualTo("ER-FIN-DADOS_INVALIDOS");
    }

    @Test
    @DisplayName("sem financeiro:close, fechar é recusado")
    @WithMockUser(authorities = Permissao.FINANCEIRO_LER_TODOS)
    void sem_permissao_nao_fecha() {
        UUID salao = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        assertThatThrownBy(() -> TenantContext.executar(salao, () -> fecharPeriodo.executar(
                LocalDate.now().minusDays(5), LocalDate.now(), "versao-nao-importa")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("sem financeiro:close, nem a prévia — ela mostra o salário de todo mundo")
    @WithMockUser(authorities = Permissao.FINANCEIRO_LER_TODOS)
    void sem_permissao_nem_previa() {
        UUID salao = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        assertThatThrownBy(() -> TenantContext.executar(salao, () -> prever.executar(
                LocalDate.now().minusDays(5), LocalDate.now())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("um salão não fecha a conta do outro")
    void isolamento_entre_saloes() {
        cenario();
        atender(coloracao, ana, "200");
        UUID salaoB = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Outro " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        Throwable erro = catchThrowable(() -> TenantContext.executar(salaoB, () -> {
            PreviaDoFechamento previa = prever.executar(hoje.minusDays(5), hoje);
            fecharPeriodo.executar(hoje.minusDays(5), hoje, previa.versao());
        }));

        assertThat(codigoDe(erro)).as("o salão B não enxerga a comissão da Ana")
                .isEqualTo("ER-FIN-SEM_POSICOES_PARA_FECHAR");
        assertThat(extratoDe(ana, hoje.minusDays(5), hoje).saldoFinal())
                .as("e a conta dela continua aberta").isEqualTo(Money.de("60"));
    }

    @Test
    @DisplayName("fechar um período sem nenhuma posição é recusado, não finge criar documento")
    void periodo_sem_posicoes() {
        cenario();

        Throwable erro = catchThrowable(() -> fecharDe(hoje.minusDays(5), hoje));

        assertThat(codigoDe(erro)).isEqualTo("ER-FIN-SEM_POSICOES_PARA_FECHAR");
    }

    @Test
    @DisplayName("financeiro:read:own falha fechado até existir vínculo usuário-profissional")
    @WithMockUser(authorities = Permissao.FINANCEIRO_LER_PROPRIO)
    void permissao_propria_nao_aceita_id_escolhido() {
        UUID salao = UUID.randomUUID();

        assertThatThrownBy(() -> TenantContext.executar(salao, () ->
                listarFechamentos.executar(UUID.randomUUID(), LocalDate.now().minusDays(5),
                        LocalDate.now())))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> TenantContext.executar(salao, () ->
                extrato.executar(UUID.randomUUID(), LocalDate.now().minusDays(5),
                        LocalDate.now())))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> TenantContext.executar(salao, () ->
                listarVales.executar(UUID.randomUUID(), LocalDate.now().minusDays(5),
                        LocalDate.now())))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ------------------------------------------------------------------ o documento

    @Test
    @DisplayName("o contracheque fica congelado e é o que a lista devolve")
    void contracheque_congelado() {
        cenario();
        atender(coloracao, ana, "200");
        vale(ana, "50", FormaDoVale.DINHEIRO);
        fecharDe(hoje.minusDays(5), hoje);

        var lista = TenantContext.obter(tenant, () -> listarFechamentos.executar(ana,
                hoje.minusDays(30), hoje));

        assertThat(lista).singleElement().satisfies(f -> {
            assertThat(f.saldoAnterior()).isEqualTo(Money.ZERO);
            assertThat(f.movimentoDoPeriodo()).as("ganhou 60, adiantou 50")
                    .isEqualTo(Money.de("10"));
            assertThat(f.valorRepassado()).isEqualTo(Money.de("10"));
            assertThat(f.saldoATransportar()).isEqualTo(Money.ZERO);
        });
    }

    @Test
    @DisplayName("update em fechamento é recusado pelo BANCO")
    void update_em_fechamento_falha() throws java.sql.SQLException {
        cenario();
        atender(coloracao, ana, "200");
        fecharDe(hoje.minusDays(5), hoje);

        try (var conexao = comoApp(tenant);
             var ps = conexao.prepareStatement("update fechamento set valor_repassado = 0")) {
            Throwable erro = catchThrowable(ps::executeUpdate);
            assertThat(erro).isNotNull();
            assertThat(erro.getMessage()).containsIgnoringCase("permission denied");
        }
    }

    @Test
    @DisplayName("períodos sobrepostos são recusados pelo BANCO, não por trava de aplicação")
    void exclusao_de_periodos_sobrepostos() throws java.sql.SQLException {
        // Trava de aplicação não resiste a duas instâncias: as duas leem, as duas não veem
        // fechamento, as duas gravam — e a pessoa recebe duas vezes.
        cenario();
        comissaoEm(ana, "60", hoje.minusDays(20));
        fecharDe(hoje.minusDays(25), hoje.minusDays(15));

        try (var conexao = comoApp(tenant);
             var ps = conexao.prepareStatement(
                     "insert into fechamento (estabelecimento_id, profissional_id, periodo_inicio,"
                             + " periodo_fim, saldo_anterior, movimento_do_periodo,"
                             + " valor_repassado, criado_em)"
                             + " values (?, ?, ?, ?, 0, 0, 0, now())")) {
            ps.setObject(1, tenant);
            ps.setObject(2, ana);
            ps.setObject(3, java.sql.Date.valueOf(hoje.minusDays(20)));
            ps.setObject(4, java.sql.Date.valueOf(hoje.minusDays(10)));
            Throwable erro = catchThrowable(ps::executeUpdate);
            assertThat(erro).isNotNull();
            assertThat(erro.getMessage()).containsIgnoringCase("fechamento_sem_sobreposicao");
        }
    }

    @Test
    @DisplayName("dois meses concorrentes não pagam o mesmo saldo duas vezes")
    void fechamentos_concorrentes_sao_serializados_por_salao() throws Exception {
        cenario();
        LocalDate inicioA = hoje.minusDays(20);
        LocalDate fimA = hoje.minusDays(11);
        LocalDate inicioB = hoje.minusDays(10);
        comissaoEm(ana, "60", hoje.minusDays(15));
        comissaoEm(ana, "40", hoje.minusDays(5));

        PreviaDoFechamento previaA = TenantContext.obter(tenant,
                () -> prever.executar(inicioA, fimA));
        PreviaDoFechamento previaB = TenantContext.obter(tenant,
                () -> prever.executar(inicioB, hoje));
        CountDownLatch largada = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> fecharConcorrente(inicioA, fimA, previaA.versao(),
                    largada));
            var b = executor.submit(() -> fecharConcorrente(inicioB, hoje, previaB.versao(),
                    largada));
            largada.countDown();

            // A thread vencedora devolve null. List.of proíbe null e mascarava justamente o
            // resultado esperado com NullPointerException antes das asserções.
            List<Throwable> resultados = java.util.Arrays.asList(
                    a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertThat(resultados).filteredOn(java.util.Objects::isNull).hasSize(1);
            assertThat(resultados).filteredOn(java.util.Objects::nonNull).singleElement()
                    .isInstanceOf(ErroDeDominio.class);
        }

        Money aindaAReceber = extratoDe(ana, inicioA, hoje).saldoFinal();
        assertThat(aindaAReceber).as("nunca fica negativo por repasse duplicado")
                .isIn(Money.ZERO, Money.de("40"));

        // Se março venceu a corrida, abril ficou com prévia velha e precisa ser conferido de novo.
        if (aindaAReceber.equals(Money.de("40"))) {
            fecharDe(inicioB, hoje);
        }
        assertThat(extratoDe(ana, inicioA, hoje).saldoFinal()).isEqualTo(Money.ZERO);
    }

    private Throwable fecharConcorrente(LocalDate de, LocalDate ate, String versao,
                                        CountDownLatch largada) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("dona", null,
                        List.of(new SimpleGrantedAuthority(Permissao.FINANCEIRO_FECHAR))));
        try {
            if (!largada.await(5, TimeUnit.SECONDS)) {
                return new AssertionError("as duas chamadas não ficaram prontas para a largada");
            }
            return catchThrowable(() -> TenantContext.executar(tenant,
                    () -> fecharPeriodo.executar(de, ate, versao)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return e;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // ------------------------------------------------------------------ risco R-06

    @Test
    @DisplayName("um mês sintético inteiro bate centavo a centavo")
    void mes_sintetico_bate_centavo_a_centavo() {
        // R-06, mitigação escrita em 11-fragilidades-e-riscos: "um teste que fecha um mês
        // sintético inteiro e compara com o valor esperado, centavo a centavo".
        //
        // Os preços não são redondos de propósito: 30% de R$ 99,90 é R$ 29,97, e é aí que uma
        // conta com escala errada começa a divergir de centavo em centavo até o dono notar no fim
        // do mês.
        //
        // O desconto fica de fora: com `desconto_afeta_comissao = false`, que é o padrão, ele não
        // chega à comissão — a regra é de RT-ATD-005 e quem a prova é `AplicarDescontoIT`.
        cenario();

        // Ana, 30% de R$ 200,00 → R$ 60,00.
        atender(coloracao, ana, "200");

        // Ana de novo, com preço quebrado: 30% de R$ 99,90 → R$ 29,97.
        atender(corte, ana, "99.90");

        // Reaberta e refechada: comissão, estorno, comissão. Líquido de um atendimento só.
        UUID refeita = atender(coloracao, ana, "200");
        TenantContext.executar(tenant, () -> reabrir.executar(refeita, "corrigir"));
        TenantContext.executar(tenant, () -> fechar.executar(refeita));

        // Bia, 50% de R$ 99,90 → R$ 49,95.
        atender(corte, bia, "99.90");

        vale(ana, "50", FormaDoVale.DINHEIRO);
        vale(bia, "120", FormaDoVale.PIX);

        var feitos = fecharDe(hoje.minusDays(5), hoje);

        // Ana: 60,00 + 29,97 + 60,00 = 149,97 de comissão, menos 50,00 de vale.
        assertThat(doProfissional(feitos, ana).movimentoDoPeriodo()).isEqualTo(Money.de("99.97"));
        assertThat(doProfissional(feitos, ana).valorRepassado()).isEqualTo(Money.de("99.97"));
        assertThat(doProfissional(feitos, ana).saldoATransportar()).isEqualTo(Money.ZERO);

        // Bia: 49,95 de comissão, menos 120,00 de vale. Não recebe, e fica devendo 70,05.
        assertThat(doProfissional(feitos, bia).valorRepassado()).isEqualTo(Money.ZERO);
        assertThat(doProfissional(feitos, bia).saldoATransportar()).isEqualTo(Money.de("-70.05"));

        Money total = feitos.stream().map(FechamentoDoProfissional::valorRepassado)
                .reduce(Money.ZERO, Money::somar);
        assertThat(total).isEqualTo(Money.de("99.97"));

        // E as duas contas ficam consistentes com o razão, que é a fonte da verdade.
        assertThat(extratoDe(ana, hoje.minusDays(5), hoje).saldoFinal()).isEqualTo(Money.ZERO);
        assertThat(extratoDe(bia, hoje.minusDays(5), hoje).saldoFinal())
                .isEqualTo(Money.de("-70.05"));
    }
}
