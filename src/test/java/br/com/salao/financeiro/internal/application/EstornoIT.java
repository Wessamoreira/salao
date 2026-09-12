package br.com.salao.financeiro.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.equipe.api.TipoDeVinculo;
import br.com.salao.equipe.internal.application.CadastrarProfissionalUseCase;
import br.com.salao.equipe.internal.application.DadosDoProfissional;
import br.com.salao.financeiro.api.ExtratoDoProfissional;
import br.com.salao.financeiro.api.FechamentoDoProfissional;
import br.com.salao.financeiro.api.Lancamento;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-FIN-007 — estorno de lançamento e de fechamento. */
@WithMockUser(authorities = { Permissao.FINANCEIRO_FECHAR, Permissao.FINANCEIRO_LER_TODOS,
        Permissao.CONFIG_GERENCIAR, Permissao.USUARIO_GERENCIAR })
class EstornoIT extends AbstractPostgresIT {

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarProfissionalUseCase cadastrarProfissional;
    @Autowired private LancamentosJdbc lancamentos;
    @Autowired private PreverFechamentoUseCase prever;
    @Autowired private FecharPeriodoUseCase fecharPeriodo;
    @Autowired private EstornarFechamentoUseCase estornarFechamento;
    @Autowired private EstornarLancamentoUseCase estornarLancamento;
    @Autowired private ConsultarExtratoUseCase extrato;
    @Autowired private Relogio relogio;

    private UUID tenant;
    private UUID ana;
    private LocalDate hoje;

    private void cenario() {
        tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        hoje = TenantContext.obter(tenant,
                () -> relogio.hojeEm(java.time.ZoneId.of("America/Sao_Paulo")));
        ana = profissional("Ana Ribeiro", "11144477735");
    }

    private UUID profissional(String nome, String documento) {
        return TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional(nome, null, documento, TipoDeVinculo.EMPREGADO,
                        null, null, new BigDecimal("30"), null, null, null))).id();
    }

    private UUID comissaoEm(UUID profissionalId, String valor, LocalDate competencia) {
        return TenantContext.obter(tenant, () -> lancamentos.inserir(tenant, competencia,
                TipoDeLancamento.COMISSAO, "COMANDA", UUID.randomUUID(), "PROFISSIONAL",
                profissionalId, Money.de(valor).negado(), "Serviço", null,
                relogio.agora(), null));
    }

    private FechamentoDoProfissional fecharDe(LocalDate de, LocalDate ate) {
        return TenantContext.obter(tenant, () -> {
            PreviaDoFechamento previa = prever.executar(de, ate);
            return fecharPeriodo.executar(de, ate, previa.versao()).getFirst();
        });
    }

    private ExtratoDoProfissional extratoDe(LocalDate de, LocalDate ate) {
        return TenantContext.obter(tenant, () -> extrato.executar(ana, de, ate));
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------- fechamento

    @Test
    @DisplayName("estornar o fechamento devolve a dívida NA COMPETÊNCIA DO PERÍODO")
    void estorno_volta_na_competencia_do_periodo() {
        /*
         * É a asserção central da rotina.
         *
         * `Razao.estornarOrigem` escreve o ESTORNO com a competência de HOJE, o que está certo
         * para reabrir comanda — é fato novo. Para o repasse, não: RN-FIN-017 dá a ele uma
         * competência ESCOLHIDA (o fim do período), e anulá-lo em outra data deixaria o período
         * liberado com a soma acumulada até o fim dele ainda contando o repasse original.
         *
         * Refechar pagaria zero, e a liberação não liberaria nada.
         */
        cenario();
        LocalDate de = hoje.minusDays(25);
        LocalDate ate = hoje.minusDays(15);
        comissaoEm(ana, "60", hoje.minusDays(20));
        FechamentoDoProfissional feito = fecharDe(de, ate);
        assertThat(extratoDe(de, ate).saldoFinal()).isEqualTo(Money.ZERO);

        TenantContext.executar(tenant,
                () -> estornarFechamento.executar(feito.id(), "repasse feito para a pessoa errada"));

        assertThat(extratoDe(de, ate).saldoFinal())
                .as("a dívida voltou DENTRO do período, não em outro mês")
                .isEqualTo(Money.de("60"));
    }

    @Test
    @DisplayName("período estornado pode ser refechado, e paga o mesmo valor")
    void periodo_estornado_e_refechavel() {
        cenario();
        LocalDate de = hoje.minusDays(25);
        LocalDate ate = hoje.minusDays(15);
        comissaoEm(ana, "60", hoje.minusDays(20));
        FechamentoDoProfissional primeiro = fecharDe(de, ate);
        TenantContext.executar(tenant,
                () -> estornarFechamento.executar(primeiro.id(), "conferência errada"));

        FechamentoDoProfissional segundo = fecharDe(de, ate);

        assertThat(segundo.id()).isNotEqualTo(primeiro.id());
        assertThat(segundo.valorRepassado()).isEqualTo(Money.de("60"));
        assertThat(segundo.vigente()).isTrue();
        assertThat(extratoDe(de, ate).saldoFinal()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("o documento estornado FICA, como histórico do que foi pago")
    void documento_estornado_fica() {
        // Apagar a linha perderia o registro de que o pagamento existiu — que é exatamente o que
        // se precisa para explicar o dinheiro que saiu da conta.
        cenario();
        comissaoEm(ana, "60", hoje.minusDays(20));
        FechamentoDoProfissional feito = fecharDe(hoje.minusDays(25), hoje.minusDays(15));

        FechamentoDoProfissional depois = TenantContext.obter(tenant,
                () -> estornarFechamento.executar(feito.id(), "engano"));

        assertThat(depois.id()).isEqualTo(feito.id());
        assertThat(depois.valorRepassado()).as("o quanto foi pago não muda")
                .isEqualTo(Money.de("60"));
        assertThat(depois.vigente()).isFalse();
        assertThat(depois.estornadoEm()).isNotNull();
    }

    @Test
    @DisplayName("estornar duas vezes não sobrescreve a data do primeiro estorno")
    void estorno_de_fechamento_e_idempotente() {
        cenario();
        comissaoEm(ana, "60", hoje.minusDays(20));
        FechamentoDoProfissional feito = fecharDe(hoje.minusDays(25), hoje.minusDays(15));

        var primeiro = TenantContext.obter(tenant,
                () -> estornarFechamento.executar(feito.id(), "primeiro"));
        var segundo = TenantContext.obter(tenant,
                () -> estornarFechamento.executar(feito.id(), "de novo"));

        assertThat(segundo.estornadoEm()).isEqualTo(primeiro.estornadoEm());
        assertThat(extratoDe(hoje.minusDays(25), hoje.minusDays(15)).linhas())
                .as("comissão, repasse, estorno — e nada além").hasSize(3);
    }

    @Test
    @DisplayName("fechamento estornado não conta como o último período fechado")
    void estornado_nao_barra_o_proximo() {
        // Se contasse, um fechamento anulado continuaria impedindo o período de ser refeito
        // pela regra do "não se fecha para trás" — e a liberação não liberaria nada.
        cenario();
        comissaoEm(ana, "60", hoje.minusDays(20));
        FechamentoDoProfissional feito = fecharDe(hoje.minusDays(25), hoje.minusDays(15));
        TenantContext.executar(tenant, () -> estornarFechamento.executar(feito.id(), "engano"));

        // O mesmo período, que antes seria "para trás" em relação ao fechamento anulado.
        assertThat(fecharDe(hoje.minusDays(25), hoje.minusDays(15)).vigente()).isTrue();
    }

    @Test
    @DisplayName("estorno de fechamento exige motivo")
    void estorno_de_fechamento_exige_motivo() {
        cenario();
        comissaoEm(ana, "60", hoje.minusDays(20));
        FechamentoDoProfissional feito = fecharDe(hoje.minusDays(25), hoje.minusDays(15));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> estornarFechamento.executar(feito.id(), "   ")));

        assertThat(codigoDe(erro)).isEqualTo("ER-FIN-DADOS_INVALIDOS");
    }

    @Test
    @DisplayName("estornar um fechamento que não existe é 404, não silêncio")
    void fechamento_inexistente() {
        cenario();

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> estornarFechamento.executar(UUID.randomUUID(), "qualquer")));

        assertThat(codigoDe(erro)).isEqualTo("ER-FIN-FECHAMENTO_NAO_ENCONTRADO");
    }

    // ------------------------------------------------------------- lançamento

    @Test
    @DisplayName("estornar um lançamento anula UMA linha e deixa as outras de pé")
    void estorno_de_uma_linha_so() {
        // Antes só era possível anular a origem inteira: uma comissão na pessoa errada obrigava
        // a reabrir a comanda toda, desfazendo receita, estoque e a comissão de quem estava certo.
        cenario();
        UUID errada = comissaoEm(ana, "60", hoje.minusDays(3));
        comissaoEm(ana, "40", hoje.minusDays(3));

        int anuladas = TenantContext.obter(tenant,
                () -> estornarLancamento.executar(errada, "profissional errada"));

        assertThat(anuladas).isEqualTo(1);
        assertThat(extratoDe(hoje.minusDays(5), hoje).saldoFinal())
                .as("sobrou só a comissão certa").isEqualTo(Money.de("40"));
    }

    @Test
    @DisplayName("a anulação nasce na competência da linha anulada, não hoje")
    void anulacao_herda_a_competencia() {
        cenario();
        LocalDate quando = hoje.minusDays(20);
        UUID linha = comissaoEm(ana, "60", quando);

        TenantContext.executar(tenant, () -> estornarLancamento.executar(linha, "errada"));

        List<Lancamento> doPeriodo = TenantContext.obter(tenant,
                () -> lancamentos.daContraparte("PROFISSIONAL", ana, quando, quando));
        assertThat(doPeriodo).hasSize(2);
        assertThat(doPeriodo).extracting(l -> l.tipo().name())
                .containsExactlyInAnyOrder("COMISSAO", "ESTORNO");
        assertThat(doPeriodo).allSatisfy(l ->
                assertThat(l.dataCompetencia()).isEqualTo(quando));
    }

    @Test
    @DisplayName("um estorno não se estorna")
    void estorno_de_estorno_recusado() {
        // Anular uma anulação é reaplicar o valor, e reaplicar dinheiro não pode acontecer por
        // engano de clique.
        cenario();
        UUID linha = comissaoEm(ana, "60", hoje.minusDays(3));
        TenantContext.executar(tenant, () -> estornarLancamento.executar(linha, "errada"));
        UUID oEstorno = TenantContext.obter(tenant,
                () -> lancamentos.daContraparte("PROFISSIONAL", ana,
                        hoje.minusDays(5), hoje)).stream()
                .filter(l -> l.tipo() == TipoDeLancamento.ESTORNO)
                .findFirst().orElseThrow().id();

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> estornarLancamento.executar(oEstorno, "desfazer o desfazer")));

        assertThat(codigoDe(erro)).isEqualTo("ER-FIN-ESTORNO_DE_ESTORNO");
    }

    @Test
    @DisplayName("estornar a mesma linha duas vezes não anula duas vezes")
    void estorno_de_lancamento_e_idempotente() {
        cenario();
        UUID linha = comissaoEm(ana, "60", hoje.minusDays(3));

        int primeira = TenantContext.obter(tenant,
                () -> estornarLancamento.executar(linha, "errada"));
        int segunda = TenantContext.obter(tenant,
                () -> estornarLancamento.executar(linha, "de novo"));

        assertThat(primeira).isEqualTo(1);
        assertThat(segunda).as("já estava anulada").isZero();
        assertThat(extratoDe(hoje.minusDays(5), hoje).saldoFinal()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("sem financeiro:close, estornar é recusado")
    @WithMockUser(authorities = Permissao.FINANCEIRO_LER_TODOS)
    void sem_permissao_nao_estorna() {
        UUID salao = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        assertThatThrownBy(() -> TenantContext.executar(salao,
                () -> estornarLancamento.executar(UUID.randomUUID(), "qualquer")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> TenantContext.executar(salao,
                () -> estornarFechamento.executar(UUID.randomUUID(), "qualquer")))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ------------------------------------------------------------- a permissão de coluna

    @Test
    @DisplayName("o banco aceita update em estornado_em e SÓ nele")
    void grant_de_coluna_e_preciso() throws java.sql.SQLException {
        /*
         * É o guarda da V43. A imutabilidade do contracheque (RN-FIN-020) não foi relaxada: ela
         * ficou PRECISA — uma coluna escrevível, e as que dizem quanto foi pago continuam
         * recusadas pelo banco, para a role e para quem escrever o código errado amanhã.
         *
         * Sem este teste, alguém "simplificaria" a V43 para um `grant update on fechamento` e
         * nada ficaria vermelho.
         */
        cenario();
        comissaoEm(ana, "60", hoje.minusDays(20));
        fecharDe(hoje.minusDays(25), hoje.minusDays(15));

        try (var conexao = comoApp(tenant)) {
            try (var ps = conexao.prepareStatement(
                    "update fechamento set estornado_em = now()")) {
                assertThat(ps.executeUpdate()).as("a coluna do estorno é escrevível").isOne();
            }
            conexao.rollback();

            for (String coluna : List.of("valor_repassado = 0", "saldo_anterior = 0",
                    "movimento_do_periodo = 0", "periodo_fim = current_date")) {
                try (var ps = conexao.prepareStatement("update fechamento set " + coluna)) {
                    Throwable erro = catchThrowable(ps::executeUpdate);
                    assertThat(erro).as("update em %s deveria ser recusado", coluna).isNotNull();
                    assertThat(erro.getMessage()).containsIgnoringCase("permission denied");
                }
                conexao.rollback();
            }
        }
    }
}
