package br.com.salao.financeiro.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.atendimento.internal.application.AbrirComandaUseCase;
import br.com.salao.atendimento.internal.application.AdicionarItemDeServicoUseCase;
import br.com.salao.catalogo.api.TipoDeBloco;
import br.com.salao.catalogo.internal.application.CadastrarServicoUseCase;
import br.com.salao.catalogo.internal.application.DefinirPrecoUseCase;
import br.com.salao.catalogo.internal.application.EtapaBruta;
import br.com.salao.cliente.internal.application.CadastrarClienteUseCase;
import br.com.salao.cliente.internal.application.DadosDoCliente;
import br.com.salao.equipe.api.TipoDeVinculo;
import br.com.salao.equipe.internal.application.CadastrarProfissionalUseCase;
import br.com.salao.equipe.internal.application.DadosDoProfissional;
import br.com.salao.financeiro.api.ComissaoApi;
import br.com.salao.financeiro.api.EscopoDaRegra;
import br.com.salao.financeiro.api.OrigemDaComissao;
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

/** RT-FIN-002 — a cascata de comissão. */
@WithMockUser(authorities = { Permissao.CONFIG_GERENCIAR, Permissao.USUARIO_GERENCIAR,
        Permissao.FINANCEIRO_LER_TODOS, Permissao.COMANDA_ABRIR, Permissao.CLIENTE_ESCREVER })
class CascataDeComissaoIT extends AbstractPostgresIT {

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarProfissionalUseCase cadastrarProfissional;
    @Autowired private CadastrarServicoUseCase cadastrarServico;
    @Autowired private DefinirPrecoUseCase definirPreco;
    @Autowired private CadastrarClienteUseCase cadastrarCliente;
    @Autowired private AbrirComandaUseCase abrir;
    @Autowired private AdicionarItemDeServicoUseCase adicionarServico;
    @Autowired private DefinirRegraDeComissaoUseCase definirRegra;
    @Autowired private RemoverRegraDeComissaoUseCase removerRegra;
    @Autowired private ListarRegrasDeComissaoUseCase listarRegras;
    @Autowired private ComissaoApi comissoes;

    private UUID tenant;
    private UUID ana;
    private UUID coloracao;

    /** Ana SEM percentual na ficha — é o caso que a cascata existe para atender. */
    private void cenario() {
        cenario(null);
    }

    private void cenario(BigDecimal daFicha) {
        tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        ana = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional("Ana Ribeiro", null, "11144477735",
                        TipoDeVinculo.EMPREGADO, null, null, daFicha, null, null, null))).id();
        coloracao = TenantContext.obter(tenant, () -> cadastrarServico.executar("Coloração", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 90)))).id();
    }

    private void regra(EscopoDaRegra escopo, UUID profissionalId, UUID servicoId, String pct) {
        TenantContext.executar(tenant, () -> definirRegra.executar(escopo, profissionalId,
                servicoId, new BigDecimal(pct)));
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ a ordem da cascata

    @Test
    @DisplayName("sem nada configurado: zero, e dizendo que é por FALTA DE REGRA")
    void sem_regra() {
        // Zero com origem SEM_REGRA é diferente de zero com origem PROFISSIONAL: o primeiro é
        // cadastro esquecido, o segundo é decisão. Sem a distinção, "por que não recebi comissão?"
        // teria a mesma resposta para os dois.
        cenario();

        var resolvida = TenantContext.obter(tenant, () -> comissoes.resolver(ana, coloracao));

        assertThat(resolvida.percentual()).isEqualByComparingTo("0");
        assertThat(resolvida.origem()).isEqualTo(OrigemDaComissao.SEM_REGRA);
    }

    @Test
    @DisplayName("o padrão da casa vale para quem não tem nada")
    void padrao_do_salao() {
        cenario();
        regra(EscopoDaRegra.SALAO, null, null, "30");

        var resolvida = TenantContext.obter(tenant, () -> comissoes.resolver(ana, coloracao));

        assertThat(resolvida.percentual()).isEqualByComparingTo("30");
        assertThat(resolvida.origem()).isEqualTo(OrigemDaComissao.PADRAO_DO_SALAO);
    }

    @Test
    @DisplayName("a ficha do profissional vence o padrão da casa")
    void ficha_vence_o_padrao() {
        cenario(new BigDecimal("40"));
        regra(EscopoDaRegra.SALAO, null, null, "30");

        var resolvida = TenantContext.obter(tenant, () -> comissoes.resolver(ana, coloracao));

        assertThat(resolvida.percentual()).isEqualByComparingTo("40");
        assertThat(resolvida.origem()).isEqualTo(OrigemDaComissao.PROFISSIONAL);
    }

    @Test
    @DisplayName("a regra do SERVIÇO vence a ficha — e é a decisão discutível da rotina")
    void servico_vence_a_ficha() {
        // Sem isso o nível seria decoração: toda profissional tem percentual na ficha, então uma
        // regra por serviço só alcançaria quem estivesse com o campo vazio. E ela existe para
        // proteger a margem de um serviço caro — que é onde trabalham as pessoas com percentual
        // alto. Se a ficha vencesse, a proteção falharia exatamente para quem precisa alcançar.
        cenario(new BigDecimal("40"));
        regra(EscopoDaRegra.SALAO, null, null, "25");
        regra(EscopoDaRegra.SERVICO, null, coloracao, "30");

        var resolvida = TenantContext.obter(tenant, () -> comissoes.resolver(ana, coloracao));

        assertThat(resolvida.percentual()).isEqualByComparingTo("30");
        assertThat(resolvida.origem()).isEqualTo(OrigemDaComissao.SERVICO);
    }

    @Test
    @DisplayName("e o par profissional × serviço vence tudo: é o nível da exceção")
    void par_vence_tudo() {
        cenario(new BigDecimal("40"));
        regra(EscopoDaRegra.SALAO, null, null, "25");
        regra(EscopoDaRegra.SERVICO, null, coloracao, "30");
        regra(EscopoDaRegra.PROFISSIONAL_SERVICO, ana, coloracao, "45");

        var resolvida = TenantContext.obter(tenant, () -> comissoes.resolver(ana, coloracao));

        assertThat(resolvida.percentual()).isEqualByComparingTo("45");
        assertThat(resolvida.origem()).isEqualTo(OrigemDaComissao.PROFISSIONAL_SERVICO);
    }

    @Test
    @DisplayName("a regra do par não alcança outra pessoa nem outro serviço")
    void par_e_especifico() {
        cenario();
        UUID bia = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional("Bia Nunes", null, "52998224725",
                        TipoDeVinculo.EMPREGADO, null, null, null, null, null, null))).id();
        UUID escova = TenantContext.obter(tenant, () -> cadastrarServico.executar("Escova", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 45)))).id();
        regra(EscopoDaRegra.SALAO, null, null, "25");
        regra(EscopoDaRegra.PROFISSIONAL_SERVICO, ana, coloracao, "45");

        assertThat(TenantContext.obter(tenant, () -> comissoes.resolver(bia, coloracao))
                .origem()).isEqualTo(OrigemDaComissao.PADRAO_DO_SALAO);
        assertThat(TenantContext.obter(tenant, () -> comissoes.resolver(ana, escova))
                .origem()).isEqualTo(OrigemDaComissao.PADRAO_DO_SALAO);
    }

    @Test
    @DisplayName("zero configurado NÃO é o mesmo que sem regra")
    void zero_configurado() {
        // Zero é a comissão de quem trabalha por salário fixo. A origem é o que separa a decisão
        // do esquecimento.
        cenario(BigDecimal.ZERO);

        var resolvida = TenantContext.obter(tenant, () -> comissoes.resolver(ana, coloracao));

        assertThat(resolvida.percentual()).isEqualByComparingTo("0");
        assertThat(resolvida.origem()).isEqualTo(OrigemDaComissao.PROFISSIONAL);
    }

    // ------------------------------------------------------------------ definir e remover

    @Test
    @DisplayName("definir de novo substitui, em vez de criar uma segunda regra")
    void definir_substitui() {
        // Duas regras para a mesma combinação fariam a cascata escolher uma ao acaso.
        cenario();
        regra(EscopoDaRegra.SERVICO, null, coloracao, "30");
        regra(EscopoDaRegra.SERVICO, null, coloracao, "35");

        assertThat(TenantContext.obter(tenant, () -> listarRegras.executar())).hasSize(1);
        assertThat(TenantContext.obter(tenant, () -> comissoes.resolver(ana, coloracao))
                .percentual()).isEqualByComparingTo("35");
    }

    @Test
    @DisplayName("remover uma regra devolve a decisão ao nível de baixo")
    void remover_cai_um_nivel() {
        cenario(new BigDecimal("40"));
        regra(EscopoDaRegra.SERVICO, null, coloracao, "30");
        UUID id = TenantContext.obter(tenant, () -> listarRegras.executar()).get(0).id();

        TenantContext.executar(tenant, () -> removerRegra.executar(id));

        var resolvida = TenantContext.obter(tenant, () -> comissoes.resolver(ana, coloracao));
        assertThat(resolvida.origem()).isEqualTo(OrigemDaComissao.PROFISSIONAL);
        assertThat(resolvida.percentual()).isEqualByComparingTo("40");
    }

    @Test
    @DisplayName("a lista vem na ordem da cascata, não em ordem alfabética")
    void lista_na_ordem_da_cascata() {
        cenario();
        regra(EscopoDaRegra.SALAO, null, null, "25");
        regra(EscopoDaRegra.PROFISSIONAL_SERVICO, ana, coloracao, "45");
        regra(EscopoDaRegra.SERVICO, null, coloracao, "30");

        assertThat(TenantContext.obter(tenant, () -> listarRegras.executar()))
                .extracting(r -> r.escopo().name())
                .containsExactly("PROFISSIONAL_SERVICO", "SERVICO", "SALAO");
    }

    @Test
    @DisplayName("a ordem do SQL bate com a de EscopoDaRegra.peso")
    void ordem_do_sql_bate_com_o_peso() {
        // A precedência está escrita duas vezes: em texto no `order by` e em número no enum.
        // Duplicação que diverge em silêncio — este teste é o que a impede.
        cenario();
        regra(EscopoDaRegra.SALAO, null, null, "25");
        regra(EscopoDaRegra.PROFISSIONAL_SERVICO, ana, coloracao, "45");
        regra(EscopoDaRegra.SERVICO, null, coloracao, "30");

        var doSql = TenantContext.obter(tenant, () -> listarRegras.executar()).stream()
                .map(r -> r.escopo().peso()).toList();

        assertThat(doSql).isSortedAccordingTo((a, b) -> b - a);
        assertThat(doSql).containsExactly(3, 2, 1);
    }

    // ------------------------------------------------------------------ o que é recusado

    @Test
    @DisplayName("escopo SALAO não leva serviço nem profissional")
    void salao_nao_leva_ids() {
        // Ignorar em silêncio faria alguém achar que definiu "coloração paga 30%" quando definiu
        // "a casa paga 30%" — e a diferença só apareceria no extrato.
        cenario();

        assertThat(codigoDe(catchThrowable(() ->
                regra(EscopoDaRegra.SALAO, null, coloracao, "30"))))
                .isEqualTo("ER-FIN-DADOS_INVALIDOS");
        assertThat(codigoDe(catchThrowable(() ->
                regra(EscopoDaRegra.SERVICO, ana, coloracao, "30"))))
                .isEqualTo("ER-FIN-DADOS_INVALIDOS");
    }

    @Test
    @DisplayName("serviço de outro salão é recusado — a FK não separaria tenant")
    void servico_de_outro_salao() {
        cenario();
        UUID outro = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Outro " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        UUID servicoDoOutro = TenantContext.obter(outro, () -> cadastrarServico.executar(
                "Corte", null, List.of(new EtapaBruta(TipoDeBloco.ATIVO, 30)))).id();

        assertThat(codigoDe(catchThrowable(() ->
                regra(EscopoDaRegra.SERVICO, null, servicoDoOutro, "30"))))
                .isEqualTo("ER-FIN-DADOS_INVALIDOS");
    }

    @Test
    @DisplayName("percentual fora de 0 a 100 é recusado")
    void percentual_invalido() {
        cenario();

        assertThat(codigoDe(catchThrowable(() ->
                regra(EscopoDaRegra.SALAO, null, null, "-1"))))
                .isEqualTo("ER-FIN-DADOS_INVALIDOS");
        assertThat(codigoDe(catchThrowable(() ->
                regra(EscopoDaRegra.SALAO, null, null, "101"))))
                .isEqualTo("ER-FIN-DADOS_INVALIDOS");
    }

    @Test
    @DisplayName("um salão não usa a regra do outro")
    void isolamento_entre_saloes() {
        cenario();
        regra(EscopoDaRegra.SALAO, null, null, "30");
        UUID anaDoA = ana;
        UUID coloracaoDoA = coloracao;
        UUID salaoB = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Outro " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        assertThat(TenantContext.obter(salaoB, () -> comissoes.resolver(anaDoA, coloracaoDoA))
                .origem()).isEqualTo(OrigemDaComissao.SEM_REGRA);
    }

    // ------------------------------------------------------------------ o lançamento usa a cascata

    @Test
    @DisplayName("o item lançado grava o percentual DA CASCATA, com a origem que venceu")
    void item_usa_a_cascata() {
        // Antes de RT-FIN-002 o lançamento lia a ficha direto. Ler a ficha do lado do atendimento
        // reescreveria, fora da fronteira, a regra que só o financeiro conhece.
        cenario(new BigDecimal("40"));
        regra(EscopoDaRegra.SERVICO, null, coloracao, "30");
        TenantContext.executar(tenant,
                () -> definirPreco.executar(coloracao, Money.de("200"), LocalDate.now().minusDays(1)));
        UUID maria = TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                new DadosDoCliente("Maria Silva", null, null, null, null)));
        UUID comanda = TenantContext.obter(tenant, () -> abrir.avulsa(maria)).comanda().id();

        var detalhada = TenantContext.obter(tenant,
                () -> adicionarServico.executar(comanda, coloracao, ana, null, null));

        var executante = detalhada.itens().get(0).executantes().get(0);
        assertThat(executante.percentualComissao()).isEqualByComparingTo("30");
        assertThat(executante.origem()).isEqualTo(OrigemDaComissao.SERVICO);
        assertThat(executante.valorComissao()).isEqualTo(Money.de("60"));
    }

    @Test
    @DisplayName("mudar a regra depois NÃO mexe no que já foi lançado")
    void snapshot_sobrevive_a_mudanca() {
        // RN-ATD-010: o percentual e a origem são snapshot. É o que faz "por que a minha comissão
        // foi 30%?" continuar tendo resposta depois que a regra deixa de existir.
        cenario(new BigDecimal("40"));
        regra(EscopoDaRegra.SERVICO, null, coloracao, "30");
        TenantContext.executar(tenant,
                () -> definirPreco.executar(coloracao, Money.de("200"), LocalDate.now().minusDays(1)));
        UUID maria = TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                new DadosDoCliente("Maria Silva", null, null, null, null)));
        UUID comanda = TenantContext.obter(tenant, () -> abrir.avulsa(maria)).comanda().id();
        var antes = TenantContext.obter(tenant,
                () -> adicionarServico.executar(comanda, coloracao, ana, null, null));

        UUID id = TenantContext.obter(tenant, () -> listarRegras.executar()).get(0).id();
        TenantContext.executar(tenant, () -> removerRegra.executar(id));

        var executante = antes.itens().get(0).executantes().get(0);
        assertThat(executante.percentualComissao()).isEqualByComparingTo("30");
        assertThat(executante.origem()).isEqualTo(OrigemDaComissao.SERVICO);
    }
}
