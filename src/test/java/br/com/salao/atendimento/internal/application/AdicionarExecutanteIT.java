package br.com.salao.atendimento.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.atendimento.api.ComandaDetalhada;
import br.com.salao.atendimento.api.OrigemDaComissao;
import br.com.salao.atendimento.api.PapelDoExecutante;
import br.com.salao.atendimento.internal.infra.ComandasJdbc;
import br.com.salao.catalogo.api.TipoDeBloco;
import br.com.salao.catalogo.internal.application.CadastrarServicoUseCase;
import br.com.salao.catalogo.internal.application.DefinirPrecoUseCase;
import br.com.salao.catalogo.internal.application.EtapaBruta;
import br.com.salao.cliente.internal.application.CadastrarClienteUseCase;
import br.com.salao.cliente.internal.application.DadosDoCliente;
import br.com.salao.equipe.api.TipoDeVinculo;
import br.com.salao.equipe.internal.application.CadastrarProfissionalUseCase;
import br.com.salao.equipe.internal.application.DadosDoProfissional;
import br.com.salao.equipe.internal.application.DefinirAtivacaoDoProfissionalUseCase;
import br.com.salao.equipe.internal.application.DefinirHabilidadesUseCase;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-ATD-005 — o assistente que lava o cabelo entra na conta. */
@WithMockUser(authorities = { Permissao.COMANDA_ABRIR, Permissao.COMANDA_DESCONTO,
        Permissao.CLIENTE_ESCREVER, Permissao.USUARIO_GERENCIAR, Permissao.CONFIG_GERENCIAR })
class AdicionarExecutanteIT extends AbstractPostgresIT {

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarClienteUseCase cadastrarCliente;
    @Autowired private CadastrarProfissionalUseCase cadastrarProfissional;
    @Autowired private DefinirHabilidadesUseCase definirHabilidades;
    @Autowired private DefinirAtivacaoDoProfissionalUseCase ativacao;
    @Autowired private CadastrarServicoUseCase cadastrarServico;
    @Autowired private DefinirPrecoUseCase definirPreco;
    @Autowired private AbrirComandaUseCase abrir;
    @Autowired private AdicionarItemDeServicoUseCase adicionarItem;
    @Autowired private AdicionarExecutanteUseCase adicionar;
    @Autowired private RemoverExecutanteUseCase remover;
    @Autowired private ComandasJdbc comandas;

    private UUID tenant;
    private UUID comanda;
    private UUID item;
    private UUID escova;
    private UUID ana;

    /** Comanda aberta com uma escova de R$ 80,00 executada pela Ana, que ganha 40%. */
    private void cenario() {
        tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
        UUID maria = TenantContext.obter(tenant, () -> cadastrarCliente.executar(
                new DadosDoCliente("Maria Silva", null, null, null, null)));
        ana = profissional("Ana Ribeiro", "11144477735", new BigDecimal("40.00"));
        escova = TenantContext.obter(tenant, () -> cadastrarServico.executar("Escova", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 45)))).id();
        TenantContext.executar(tenant,
                () -> definirPreco.executar(escova, Money.de("80"), LocalDate.now().minusDays(1)));
        comanda = TenantContext.obter(tenant, () -> abrir.avulsa(maria)).comanda().id();
        item = TenantContext.obter(tenant,
                () -> adicionarItem.executar(comanda, escova, ana, null, null)).itens().get(0).id();
    }

    private UUID profissional(String nome, String documento, BigDecimal comissao) {
        return TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional(nome, null, documento, TipoDeVinculo.EMPREGADO,
                        null, null, comissao, null, null, null))).id();
    }

    private ComandaDetalhada acrescentar(UUID profissionalId, String percentual) {
        return TenantContext.obter(tenant, () -> adicionar.executar(
                comanda, item, profissionalId, new BigDecimal(percentual)));
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ o caminho normal

    @Test
    @DisplayName("o assistente entra com percentual próprio, sobre a mesma base")
    void assistente_entra() {
        cenario();
        UUID bia = profissional("Bia Nunes", "52998224725", new BigDecimal("40.00"));

        var detalhada = acrescentar(bia, "10");

        var executantes = detalhada.itens().get(0).executantes();
        assertThat(executantes).hasSize(2);
        var assistente = executantes.stream()
                .filter(e -> e.papel() == PapelDoExecutante.ASSISTENTE).findFirst().orElseThrow();
        assertThat(assistente.profissionalId()).isEqualTo(bia);
        assertThat(assistente.percentualComissao()).isEqualByComparingTo("10");
        assertThat(assistente.baseCalculo()).isEqualTo(Money.de("80"));
        assertThat(assistente.valorComissao()).isEqualTo(Money.de("8"));
    }

    @Test
    @DisplayName("o percentual do assistente NÃO vem da ficha dele, e a origem diz isso")
    void percentual_e_informado() {
        // Quem lava também pode ser cabeleireira, e o percentual dela como cabeleireira não tem
        // nada a ver com o que ela ganha por lavar.
        cenario();
        UUID bia = profissional("Bia Nunes", "52998224725", new BigDecimal("45.00"));

        var assistente = acrescentar(bia, "10").itens().get(0).executantes().stream()
                .filter(e -> e.papel() == PapelDoExecutante.ASSISTENTE).findFirst().orElseThrow();

        assertThat(assistente.percentualComissao()).isEqualByComparingTo("10");
        assertThat(assistente.origem()).isEqualTo(OrigemDaComissao.INFORMADA_NO_ITEM);
    }

    @Test
    @DisplayName("o assistente NÃO precisa executar o serviço")
    void assistente_dispensa_habilidade() {
        // Exigir a habilidade obrigaria a cadastrar toda assistente como colorista — e aí a agenda
        // passaria a oferecê-la para marcar coloração sozinha.
        cenario();
        UUID corte = TenantContext.obter(tenant, () -> cadastrarServico.executar("Corte", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 30)))).id();
        UUID bia = profissional("Bia Nunes", "52998224725", BigDecimal.ZERO);
        TenantContext.executar(tenant,
                () -> definirHabilidades.executar(bia, false, Set.of(corte), Map.of()));

        var detalhada = acrescentar(bia, "10");

        assertThat(detalhada.itens().get(0).executantes()).hasSize(2);
    }

    // ------------------------------------------------------------------ o que é recusado

    @Test
    @DisplayName("a mesma pessoa não entra duas vezes no item")
    void nao_entra_duas_vezes() {
        cenario();
        UUID bia = profissional("Bia Nunes", "52998224725", BigDecimal.ZERO);
        acrescentar(bia, "10");

        Throwable erro = catchThrowable(() -> acrescentar(bia, "5"));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-JA_ESTA_NO_ITEM");
    }

    @Test
    @DisplayName("nem o principal: ele já está no item")
    void principal_nao_entra_de_novo() {
        cenario();

        Throwable erro = catchThrowable(() -> acrescentar(ana, "10"));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-JA_ESTA_NO_ITEM");
    }

    @Test
    @DisplayName("as comissões do item não passam de 100%")
    void nao_passa_de_cem() {
        // O jeito de chegar lá é digitar 90 onde se queria 9 — e o erro não aparece na comanda,
        // que fecha normalmente. Só vira problema quando o repasse do mês não bate.
        cenario();
        UUID bia = profissional("Bia Nunes", "52998224725", BigDecimal.ZERO);

        Throwable erro = catchThrowable(() -> acrescentar(bia, "70"));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-COMISSAO_PASSA_DE_CEM");
        assertThat(erro).hasMessageContaining("40");
    }

    @Test
    @DisplayName("percentual fora de 0 a 100 é recusado")
    void percentual_invalido() {
        cenario();
        UUID bia = profissional("Bia Nunes", "52998224725", BigDecimal.ZERO);

        assertThat(codigoDe(catchThrowable(() -> acrescentar(bia, "-1"))))
                .isEqualTo("ER-ATD-PERCENTUAL_INVALIDO");
        assertThat(codigoDe(catchThrowable(() -> acrescentar(bia, "101"))))
                .isEqualTo("ER-ATD-PERCENTUAL_INVALIDO");
    }

    @Test
    @DisplayName("profissional inativo não ajuda ninguém")
    void inativo_nao_entra() {
        cenario();
        UUID bia = profissional("Bia Nunes", "52998224725", BigDecimal.ZERO);
        TenantContext.executar(tenant, () -> ativacao.executar(bia, false));

        Throwable erro = catchThrowable(() -> acrescentar(bia, "10"));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-PROFISSIONAL_NAO_ENCONTRADO");
    }

    @Test
    @DisplayName("comanda que não está aberta não recebe assistente")
    void comanda_fechada() throws SQLException {
        cenario();
        UUID bia = profissional("Bia Nunes", "52998224725", BigDecimal.ZERO);
        try (var ps = comoOwner().prepareStatement(
                "update comanda set status = 'FECHADA' where id = ?")) {
            ps.setObject(1, comanda);
            ps.executeUpdate();
        }

        Throwable erro = catchThrowable(() -> acrescentar(bia, "10"));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-COMANDA_NAO_ESTA_ABERTA");
    }

    @Test
    @DisplayName("produto não tem quem o execute")
    void produto_nao_tem_executante() throws SQLException {
        // O item de produto é de RT-ATD-003 e ainda não tem rotina; a linha é forjada no banco
        // para que a regra já esteja valendo quando ela chegar.
        cenario();
        UUID bia = profissional("Bia Nunes", "52998224725", BigDecimal.ZERO);
        UUID itemDeProduto;
        try (var ps = comoOwner().prepareStatement("""
                insert into item_comanda (estabelecimento_id, comanda_id, tipo, produto_id,
                                          descricao, preco_unitario, valor_total, criado_em)
                values (?, ?, 'PRODUTO', gen_random_uuid(), 'Shampoo', 30, 30, now())
                returning id
                """)) {
            ps.setObject(1, tenant);
            ps.setObject(2, comanda);
            try (var rs = ps.executeQuery()) {
                rs.next();
                itemDeProduto = rs.getObject(1, UUID.class);
            }
        }

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> adicionar.executar(comanda, itemDeProduto, bia, BigDecimal.TEN)));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-ITEM_NAO_E_SERVICO");
    }

    // ------------------------------------------------------------------ remover

    @Test
    @DisplayName("remover o assistente devolve o item ao que era")
    void remover_assistente() {
        cenario();
        UUID bia = profissional("Bia Nunes", "52998224725", BigDecimal.ZERO);
        UUID assistenteId = acrescentar(bia, "10").itens().get(0).executantes().stream()
                .filter(e -> e.papel() == PapelDoExecutante.ASSISTENTE)
                .findFirst().orElseThrow().id();

        var detalhada = TenantContext.obter(tenant,
                () -> remover.executar(comanda, item, assistenteId));

        assertThat(detalhada.itens().get(0).executantes()).hasSize(1);
        assertThat(detalhada.itens().get(0).executantes().get(0).papel())
                .isEqualTo(PapelDoExecutante.PRINCIPAL);
    }

    @Test
    @DisplayName("o principal não sai: item sem principal é serviço que ninguém executou")
    void principal_nao_sai() {
        cenario();
        UUID principalId = TenantContext.obter(tenant, () -> comandas.itensDe(comanda))
                .get(0).executantes().get(0).id();

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> remover.executar(comanda, item, principalId)));

        assertThat(codigoDe(erro)).isEqualTo("ER-ATD-PRINCIPAL_NAO_SAI");
    }

    @Test
    @DisplayName("quem recusa o segundo principal é o ÍNDICE")
    void o_indice_recusa_dois_principais() {
        // A regra valia desde RT-ATD-002 só porque ninguém tinha escrito o segundo caminho. Ao
        // abrir a porta do assistente, ela passou a precisar de garantia no banco.
        cenario();
        UUID bia = profissional("Bia Nunes", "52998224725", BigDecimal.ZERO);

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> comandas.inserirExecutante(tenant, item, bia, PapelDoExecutante.PRINCIPAL,
                        BigDecimal.TEN, OrigemDaComissao.INFORMADA_NO_ITEM)));

        assertThat(erro).hasMessageContaining("um_principal_por_item");
    }
}
