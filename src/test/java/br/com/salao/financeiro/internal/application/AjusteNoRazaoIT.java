package br.com.salao.financeiro.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.equipe.api.TipoDeVinculo;
import br.com.salao.equipe.internal.application.CadastrarProfissionalUseCase;
import br.com.salao.equipe.internal.application.DadosDoProfissional;
import br.com.salao.financeiro.api.ExtratoDoProfissional;
import br.com.salao.financeiro.api.Lancamento;
import br.com.salao.financeiro.api.RazaoApi;
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
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * RT-FIN-013 / RN-FIN-041 — o ajuste manual no razão.
 *
 * <p>Era o caminho que RN-FIN-023 oferecia por escrito para quem quer o valor de volta depois de
 * um estorno, e que não existia: {@code AJUSTE} e {@code origem_tipo = 'MANUAL'} estavam no modelo
 * sem nada que os escrevesse.
 */
@WithMockUser(authorities = { Permissao.FINANCEIRO_FECHAR, Permissao.FINANCEIRO_LER_TODOS,
        Permissao.USUARIO_GERENCIAR })
class AjusteNoRazaoIT extends AbstractPostgresIT {

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarProfissionalUseCase cadastrarProfissional;
    @Autowired private RegistrarAjusteNoRazaoUseCase ajustar;
    @Autowired private ConsultarExtratoUseCase extrato;
    @Autowired private EstornarLancamentoUseCase estornar;
    @Autowired private RazaoApi razao;

    private UUID tenant;
    private UUID ana;

    private void cenario() {
        tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        ana = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional("Ana Ribeiro", "Ana", "11144477735",
                        TipoDeVinculo.EMPREGADO, null, null, new BigDecimal("30"),
                        null, null, null))).id();
    }

    private Lancamento ajuste(String valorParaQuemRecebe, String motivo) {
        return TenantContext.obter(tenant, () ->
                ajustar.executar(ana, Money.de(valorParaQuemRecebe), motivo));
    }

    private ExtratoDoProfissional doMes() {
        LocalDate hoje = LocalDate.now();
        return TenantContext.obter(tenant, () -> extrato.executar(ana,
                hoje.withDayOfMonth(1), hoje.withDayOfMonth(hoje.lengthOfMonth())));
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    @Test
    @DisplayName("o ajuste positivo entra NEGATIVO no razão: o que a pessoa recebe sai do salão")
    void ajuste_positivo_entra_negativo_no_razao() {
        // Pedir esse sinal a quem digita seria pedir que ela conhecesse a contabilidade do razão
        // para consertar um erro de digitação — e errar o sinal custa o dobro, na direção errada.
        cenario();

        Lancamento linha = ajuste("45", "comissão apurada a menos na comanda 1420");

        assertThat(linha.tipo()).isEqualTo(TipoDeLancamento.AJUSTE);
        assertThat(linha.origemTipo()).isEqualTo("MANUAL");
        assertThat(linha.contraparteId()).isEqualTo(ana);
        assertThat(linha.valor().valor()).isEqualByComparingTo("-45");
    }

    @Test
    @DisplayName("e aparece no extrato com o sinal de quem recebe, positivo")
    void aparece_no_extrato_no_sinal_de_quem_recebe() {
        cenario();
        ajuste("45", "comissão apurada a menos");

        var e = doMes();

        assertThat(e.aReceber().valor()).isEqualByComparingTo("45");
        assertThat(e.linhas()).singleElement().satisfies(linha -> {
            assertThat(linha.tipo()).isEqualTo(TipoDeLancamento.AJUSTE);
            assertThat(linha.valor().valor()).isEqualByComparingTo("45");
            assertThat(linha.descricao()).contains("comissão apurada a menos");
        });
    }

    @Test
    @DisplayName("o ajuste negativo reduz o que a pessoa tem a receber")
    void ajuste_negativo_reduz() {
        cenario();

        ajuste("-30", "produto levado e não lançado");

        assertThat(doMes().aReceber().valor()).isEqualByComparingTo("-30");
    }

    @Test
    @DisplayName("a linha carrega o id do razão, que é por onde a tela a estorna")
    void linha_leva_o_id_do_razao() {
        // Sem isto, o extrato mostrava a linha e não tinha como pedir a anulação dela: só a
        // origem inteira — a comanda toda, em vez do item.
        cenario();
        Lancamento linha = ajuste("45", "corrigindo a comissão");

        var doExtrato = doMes().linhas().getFirst();

        assertThat(doExtrato.lancamentoId()).isEqualTo(linha.id());
        assertThat(TenantContext.obter(tenant, () ->
                estornar.executar(doExtrato.lancamentoId(), "ajuste feito na pessoa errada")))
                .isEqualTo(1);
        assertThat(doMes().aReceber().ehZero()).isTrue();
    }

    @Test
    @DisplayName("cada ajuste é um documento próprio: anular um não anula os outros da pessoa")
    void cada_ajuste_e_um_documento() {
        // `origem_id` não pode ser o profissional: agrupar todos os ajustes dela num "documento"
        // faria `estornarOrigem` anular a correção de janeiro junto com a de setembro.
        cenario();
        Lancamento primeiro = ajuste("45", "comissão a menos na comanda 1420");
        ajuste("30", "produto levado e não lançado");

        assertThat(TenantContext.obter(tenant, () ->
                razao.estornarOrigem("MANUAL", primeiro.origemId(), "ajuste indevido")))
                .isEqualTo(1);

        // 45 anulado, 30 de pé: o segundo ajuste continua valendo.
        assertThat(doMes().aReceber().valor()).isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("ajuste exige motivo: é o que separa correção de mexida silenciosa no livro")
    void exige_motivo() {
        cenario();

        var erro = catchThrowable(() -> TenantContext.executar(tenant, () ->
                ajustar.executar(ana, Money.de("45"), "   ")));

        assertThat(codigoDe(erro)).isEqualTo("ER-FIN-DADOS_INVALIDOS");
        assertThat(doMes().linhas()).isEmpty();
    }

    @Test
    @DisplayName("ajuste de zero é recusado: um motivo sem efeito")
    void recusa_valor_zero() {
        cenario();

        var erro = catchThrowable(() -> TenantContext.executar(tenant, () ->
                ajustar.executar(ana, Money.ZERO, "não mudei nada")));

        assertThat(codigoDe(erro)).isEqualTo("ER-FIN-DADOS_INVALIDOS");
    }

    @Test
    @DisplayName("profissional de outro salão não recebe ajuste")
    void recusa_profissional_inexistente() {
        // Não há FK para profissional (RN-EQP-015): a linha ficaria gravada sem aparecer em
        // extrato nenhum, que é a pior forma de errar com dinheiro.
        cenario();

        var erro = catchThrowable(() -> TenantContext.executar(tenant, () ->
                ajustar.executar(UUID.randomUUID(), Money.de("45"), "corrigindo")));

        assertThat(codigoDe(erro)).isEqualTo("ER-FIN-DADOS_INVALIDOS");
    }

    @Test
    @DisplayName("a competência é o dia do salão, e não vem de fora")
    void competencia_e_o_dia_do_salao() {
        // Competência escolhida por quem chama poderia ser empurrada para dentro de um período
        // já fechado, depois de o repasse ter sido pago (RN-FIN-014).
        cenario();

        Lancamento linha = ajuste("45", "corrigindo a comissão");

        assertThat(linha.dataCompetencia()).isEqualTo(LocalDate.now());
    }

    @Test
    @WithMockUser(authorities = Permissao.FINANCEIRO_ADIANTAR)
    @DisplayName("quem adianta não ajusta: escrever no razão é de quem responde pelo repasse")
    void sem_permissao_nao_ajusta() {
        assertThatThrownBy(() -> ajustar.executar(UUID.randomUUID(), Money.de("45"), "x"))
                .isInstanceOf(AccessDeniedException.class);
    }
}
