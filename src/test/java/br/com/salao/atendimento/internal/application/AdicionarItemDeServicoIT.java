package br.com.salao.atendimento.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.atendimento.api.ComandaDetalhada;
import br.com.salao.atendimento.api.OrigemDaComissao;
import br.com.salao.atendimento.api.PapelDoExecutante;
import br.com.salao.catalogo.internal.application.CadastrarServicoUseCase;
import br.com.salao.catalogo.internal.application.DefinirAtivacaoDoServicoUseCase;
import br.com.salao.catalogo.internal.application.DefinirPrecoUseCase;
import br.com.salao.catalogo.internal.application.EtapaBruta;
import br.com.salao.agenda.api.OrigemDoAgendamento;
import br.com.salao.agenda.internal.application.CriarAgendamentoUseCase;
import br.com.salao.agenda.internal.application.NovoAgendamento;
import br.com.salao.catalogo.api.TipoDeBloco;
import br.com.salao.cliente.internal.application.CadastrarClienteUseCase;
import br.com.salao.cliente.internal.application.DadosDoCliente;
import br.com.salao.equipe.api.TipoDeVinculo;
import br.com.salao.equipe.internal.application.CadastrarProfissionalUseCase;
import br.com.salao.equipe.internal.application.DadosDoProfissional;
import br.com.salao.equipe.internal.application.DefinirAtivacaoDoProfissionalUseCase;
import br.com.salao.equipe.internal.application.DefinirHabilidadesUseCase;
import br.com.salao.equipe.internal.application.DefinirJornadaUseCase;
import br.com.salao.equipe.internal.application.PeriodoBruto;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-ATD-002 — lançar serviço na comanda, com quem executou. */
@WithMockUser(authorities = { Permissao.COMANDA_ABRIR, Permissao.COMANDA_FECHAR,
        Permissao.CLIENTE_ESCREVER, Permissao.USUARIO_GERENCIAR, Permissao.CONFIG_GERENCIAR,
        Permissao.AGENDA_ESCREVER_TODAS })
class AdicionarItemDeServicoIT extends AbstractPostgresIT {

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarClienteUseCase cadastrarCliente;
    @Autowired private CadastrarProfissionalUseCase cadastrarProfissional;
    @Autowired private DefinirHabilidadesUseCase definirHabilidades;
    @Autowired private DefinirAtivacaoDoProfissionalUseCase ativacaoDoProfissional;
    @Autowired private CadastrarServicoUseCase cadastrarServico;
    @Autowired private DefinirAtivacaoDoServicoUseCase ativacaoDoServico;
    @Autowired private DefinirPrecoUseCase definirPreco;
    @Autowired private AbrirComandaUseCase abrir;
    @Autowired private AdicionarItemDeServicoUseCase adicionar;
    @Autowired private RemoverItemUseCase remover;
    @Autowired private DefinirJornadaUseCase definirJornada;
    @Autowired private CriarAgendamentoUseCase criarAgendamento;

    private UUID tenant;
    private UUID maria;
    private UUID comanda;
    private UUID ana;
    private UUID escova;

    /** Um salão com uma cliente na cadeira, uma profissional e um serviço com preço. */
    private void cenario() {
        cenario(new BigDecimal("40.00"), Money.de("80"));
    }

    private void cenario(BigDecimal comissao, Money preco) {
        tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        maria = TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                new DadosDoCliente("Maria Silva", null, null, null, null)));
        ana = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional("Ana Ribeiro", "Ana", "11144477735",
                        TipoDeVinculo.EMPREGADO, null, null, comissao, null, null, null))).id();
        escova = TenantContext.obter(tenant, () -> cadastrarServico.executar("Escova", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 45)))).id();
        if (preco != null) {
            TenantContext.executar(tenant,
                    () -> definirPreco.executar(escova, preco, LocalDate.now().minusDays(1)));
        }
        comanda = TenantContext.obter(tenant, () -> abrir.avulsa(maria)).comanda().id();
    }

    private ComandaDetalhada lancar() {
        return TenantContext.obter(tenant,
                () -> adicionar.executar(comanda, escova, ana, null, null));
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ o caminho normal

    @Test
    @DisplayName("lança o serviço e a comanda passa a somar")
    void lanca_servico() {
        cenario();

        var detalhada = lancar();

        assertThat(detalhada.itens()).hasSize(1);
        var item = detalhada.itens().get(0);
        assertThat(item.descricao()).isEqualTo("Escova");
        assertThat(item.precoUnitario()).isEqualTo(Money.de("80"));
        assertThat(item.valorTotal()).isEqualTo(Money.de("80"));
        assertThat(detalhada.comanda().valorBruto()).isEqualTo(Money.de("80"));
        assertThat(detalhada.comanda().valorLiquido()).isEqualTo(Money.de("80"));
    }

    @Test
    @DisplayName("o executante principal entra com o percentual e a comissão calculada")
    void executante_principal() {
        cenario();

        var item = lancar().itens().get(0);

        assertThat(item.executantes()).hasSize(1);
        var executante = item.executantes().get(0);
        assertThat(executante.profissionalId()).isEqualTo(ana);
        assertThat(executante.papel()).isEqualTo(PapelDoExecutante.PRINCIPAL);
        assertThat(executante.percentualComissao()).isEqualByComparingTo("40.00");
        assertThat(executante.origem()).isEqualTo(OrigemDaComissao.PROFISSIONAL);
        assertThat(executante.baseCalculo()).isEqualTo(Money.de("80"));
        assertThat(executante.valorComissao()).isEqualTo(Money.de("32"));
    }

    @Test
    @DisplayName("quantidade multiplica o total, e a comissão acompanha")
    void quantidade_multiplica() {
        cenario();

        var detalhada = TenantContext.obter(tenant, () -> adicionar.executar(
                comanda, escova, ana, new BigDecimal("2"), null));

        assertThat(detalhada.itens().get(0).valorTotal()).isEqualTo(Money.de("160"));
        assertThat(detalhada.comanda().valorBruto()).isEqualTo(Money.de("160"));
        assertThat(detalhada.itens().get(0).executantes().get(0).valorComissao())
                .isEqualTo(Money.de("64"));
    }

    @Test
    @DisplayName("dois itens somam na comanda")
    void dois_itens_somam() {
        cenario();
        lancar();

        var detalhada = lancar();

        assertThat(detalhada.itens()).hasSize(2);
        assertThat(detalhada.comanda().valorBruto()).isEqualTo(Money.de("160"));
    }

    // ------------------------------------------------------------------ o snapshot

    @Test
    @DisplayName("mudar o preço do catálogo depois NÃO mexe no item já lançado")
    void preco_e_snapshot() {
        // Sem isso, reprocessar um fechamento de três meses atrás dá número diferente do repasse
        // já pago — e ninguém consegue explicar a diferença ao profissional.
        cenario();
        lancar();

        TenantContext.executar(tenant,
                () -> definirPreco.executar(escova, Money.de("120"), LocalDate.now()));

        var detalhada = TenantContext.obter(tenant,
                () -> adicionar.executar(comanda, escova, ana, null, null));
        assertThat(detalhada.itens().get(0).precoUnitario())
                .as("o primeiro item continua com o preço do momento da venda")
                .isEqualTo(Money.de("80"));
        assertThat(detalhada.itens().get(1).precoUnitario())
                .as("o segundo pega o preço novo").isEqualTo(Money.de("120"));
        assertThat(detalhada.comanda().valorBruto()).isEqualTo(Money.de("200"));
    }

    @Test
    @DisplayName("mudar a comissão do profissional depois NÃO mexe no item já lançado")
    void percentual_e_snapshot() {
        cenario();
        lancar();

        // A pergunta que chega depois não é "quanto eu ganho", é "por que a MINHA comissão foi
        // 40% e não 50%" — e ela só tem resposta se o percentual daquele dia ficou gravado.
        UUID outra = TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional("Bia Nunes", "Bia", "52998224725",
                        TipoDeVinculo.EMPREGADO, null, null, new BigDecimal("50.00"),
                        null, null, null))).id();

        var segundo = TenantContext.obter(tenant,
                () -> adicionar.executar(comanda, escova, outra, null, null));
        assertThat(segundo.itens().get(0).executantes().get(0).percentualComissao())
                .isEqualByComparingTo("40.00");
        assertThat(segundo.itens().get(1).executantes().get(0).percentualComissao())
                .isEqualByComparingTo("50.00");
    }

    // ------------------------------------------------------------------ o que é recusado

    @Test
    @DisplayName("serviço sem preço é recusado — zero seria um brinde")
    void servico_sem_preco() {
        // O contrato do ServicoApi já diz por quê: zero é um preço, e um serviço sem preço num
        // relatório financeiro precisa aparecer como pendência de cadastro, não como cortesia.
        cenario(new BigDecimal("40.00"), null);

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> adicionar.executar(comanda, escova, ana, null, null)));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-SERVICO_SEM_PRECO");
    }

    @Test
    @DisplayName("serviço fora do cardápio não é vendido")
    void servico_desativado() {
        cenario();
        TenantContext.executar(tenant, () -> ativacaoDoServico.executar(escova, false));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> adicionar.executar(comanda, escova, ana, null, null)));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-SERVICO_FORA_DO_CARDAPIO");
    }

    @Test
    @DisplayName("quem não executa o serviço não entra como executante")
    void nao_executa_o_servico() {
        cenario();
        UUID corte = TenantContext.obter(tenant, () -> cadastrarServico.executar("Corte", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 30)))).id();
        TenantContext.executar(tenant, () -> definirHabilidades.executar(
                ana, false, Set.of(corte), Map.of()));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> adicionar.executar(comanda, escova, ana, null, null)));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-NAO_EXECUTA_O_SERVICO");
    }

    @Test
    @DisplayName("profissional inativo não executa nada")
    void profissional_inativo() {
        cenario();
        TenantContext.executar(tenant, () -> ativacaoDoProfissional.executar(ana, false));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> adicionar.executar(comanda, escova, ana, null, null)));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-PROFISSIONAL_NAO_ENCONTRADO");
    }

    @Test
    @DisplayName("quantidade zero é recusada")
    void quantidade_zero() {
        cenario();

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> adicionar.executar(comanda, escova, ana, BigDecimal.ZERO, null)));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-QUANTIDADE_INVALIDA");
    }

    @Test
    @DisplayName("comanda que não está aberta não recebe item")
    void comanda_fechada() throws SQLException {
        // Fechar é RT-ATD-006 e ainda não existe; o estado é forçado no banco de propósito, para
        // que a regra já esteja valendo quando o fechamento chegar.
        cenario();
        try (var ps = comoOwner().prepareStatement(
                "update comanda set status = 'FECHADA' where id = ?")) {
            ps.setObject(1, comanda);
            ps.executeUpdate();
        }

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> adicionar.executar(comanda, escova, ana, null, null)));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-COMANDA_NAO_ESTA_ABERTA");
    }


    // ------------------------------------------------------------------ o horário de origem

    /** Amanhã às 10h, dentro de uma jornada larga: nada aqui depende da hora do build. */
    private UUID horarioDe(UUID clienteId) {
        var semana = new HashMap<DayOfWeek, List<PeriodoBruto>>();
        for (DayOfWeek dia : DayOfWeek.values()) {
            semana.put(dia, List.of(new PeriodoBruto(LocalTime.of(6, 0), LocalTime.of(22, 0))));
        }
        TenantContext.executar(tenant, () -> definirJornada.executar(ana, semana));
        Instant quando = LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(1)
                .atTime(10, 0).atZone(ZoneId.of("America/Sao_Paulo")).toInstant();
        return TenantContext.obter(tenant, () -> criarAgendamento.executar(new NovoAgendamento(
                clienteId, ana, escova, quando, OrigemDoAgendamento.PAINEL, null, null,
                false, false, null, null))).id();
    }

    @Test
    @DisplayName("o item guarda de qual horário marcado ele veio")
    void item_guarda_o_horario() {
        // A relação com o agendamento vive no ITEM: na comanda ela seria mentira já no segundo
        // item, porque uma comanda recebe o horário das 9h e o das 14h (RN-ATD-004).
        cenario();
        UUID horario = horarioDe(maria);

        var detalhada = TenantContext.obter(tenant,
                () -> adicionar.executar(comanda, escova, ana, null, horario));

        assertThat(detalhada.itens().get(0).agendamentoId()).isEqualTo(horario);
    }

    @Test
    @DisplayName("horário de outra cliente não entra na comanda desta")
    void horario_de_outra_cliente() {
        // Sem a conferência, o relatório de "quanto rende cada horário marcado" ficaria errado
        // sem nada acusar.
        cenario();
        UUID joana = TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                new DadosDoCliente("Joana Prado", null, null, null, null)));
        UUID horarioDaJoana = horarioDe(joana);

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> adicionar.executar(comanda, escova, ana, null, horarioDaJoana)));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-AGENDAMENTO_DE_OUTRA_CLIENTE");
    }

    // ------------------------------------------------------------------ remover

    @Test
    @DisplayName("remover o item devolve o total ao que era")
    void remover_item() {
        cenario();
        UUID item = lancar().itens().get(0).id();

        var depois = TenantContext.obter(tenant, () -> remover.executar(comanda, item));

        assertThat(depois.itens()).isEmpty();
        assertThat(depois.comanda().valorBruto()).isEqualTo(Money.ZERO);
        assertThat(depois.comanda().valorLiquido()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("remover item que não é desta comanda é recusado")
    void remover_item_de_outra_comanda() {
        // Sem a conferência, "remova o item X da comanda Y" apagaria o item da comanda Z, e quem
        // olha a tela de Y não veria nada acontecer.
        cenario();
        UUID item = lancar().itens().get(0).id();
        UUID joana = TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                new DadosDoCliente("Joana Prado", null, null, null, null)));
        UUID outraComanda = TenantContext.obter(tenant,
                () -> abrir.avulsa(joana)).comanda().id();

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> remover.executar(outraComanda, item)));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-ITEM_NAO_ENCONTRADO");
    }
}
