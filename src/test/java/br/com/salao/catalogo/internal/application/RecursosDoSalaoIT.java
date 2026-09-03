package br.com.salao.catalogo.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.catalogo.api.Recurso;
import br.com.salao.catalogo.api.RecursoApi;
import br.com.salao.catalogo.api.ServicoApi;
import br.com.salao.catalogo.api.ServicoResumo;
import br.com.salao.catalogo.api.TipoDeBloco;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-CAT-003 — recurso do salão e a exigência por bloco. */
@WithMockUser(authorities = { Permissao.CONFIG_GERENCIAR, Permissao.AGENDA_LER_TODAS })
class RecursosDoSalaoIT extends AbstractPostgresIT {

    @Autowired
    private ProvisionarEstabelecimentoUseCase provisionar;

    @Autowired
    private CadastrarRecursoUseCase cadastrarRecurso;

    @Autowired
    private AtualizarRecursoUseCase atualizarRecurso;

    @Autowired
    private DefinirAtivacaoDoRecursoUseCase definirAtivacaoDoRecurso;

    @Autowired
    private ListarRecursosUseCase listarRecursos;

    @Autowired
    private CadastrarServicoUseCase cadastrarServico;

    @Autowired
    private DefinirAtivacaoDoServicoUseCase definirAtivacaoDoServico;

    @Autowired
    private RecursoApi recursos;

    @Autowired
    private ServicoApi catalogo;

    private UUID salao() {
        return provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Ana",
                "ana-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
    }

    private Recurso lavatoriosEm(UUID tenant, int quantidade) {
        return TenantContext.obter(tenant,
                () -> cadastrarRecurso.executar("Lavatório", quantidade));
    }

    /** Coloração de verdade: aplica 30, química age 40, lava e finaliza 30 — e a lavagem exige. */
    private ServicoResumo coloracaoEm(UUID tenant, UUID lavatorio) {
        return TenantContext.obter(tenant, () -> cadastrarServico.executar("Coloração", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 30),
                        new EtapaBruta(TipoDeBloco.PAUSA, 40),
                        new EtapaBruta(TipoDeBloco.ATIVO, 30, Set.of(lavatorio)))));
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    private UUID ultimoBlocoDe(UUID servicoId) throws SQLException {
        try (var ps = comoOwner().prepareStatement(
                "select id from bloco_do_servico where servico_id = ? order by ordem desc limit 1")) {
            ps.setObject(1, servicoId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    // ------------------------------------------------------------------ cadastro

    @Test
    @DisplayName("cadastra o que o salão tem, com quantas unidades")
    void cadastra_e_lista() {
        UUID tenant = salao();
        Recurso lavatorio = lavatoriosEm(tenant, 3);

        assertThat(lavatorio.quantidade()).isEqualTo(3);
        assertThat(lavatorio.ativo()).isTrue();
        assertThat(TenantContext.obter(tenant, () -> listarRecursos.executar(false)))
                .extracting(Recurso::nome).containsExactly("Lavatório");
    }

    @Test
    @DisplayName("quantidade zero é 422, não 500")
    void quantidade_zero_e_422() {
        // A sexta ocorrência da mesma família: `Recurso` recusa com IllegalArgumentException, que
        // vinda da borda viraria 500. O tradutor (RecursoBruto) entrou junto com o código.
        UUID tenant = salao();

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> cadastrarRecurso.executar("Lavatório", 0)));

        assertThat(erro).isInstanceOf(ErroDeDominio.class);
        assertThat(codigoDe(erro)).isEqualTo("ER-CAT-DADOS_INVALIDOS");
    }

    @Test
    @DisplayName("quantidade não informada é uma unidade")
    void quantidade_ausente_vira_um() {
        UUID tenant = salao();

        assertThat(TenantContext.obter(tenant,
                () -> cadastrarRecurso.executar("Sala de estética", null)).quantidade())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("nome único ignorando caixa: dois 'Lavatório' dobrariam a capacidade no papel")
    void nome_unico_ignorando_caixa() {
        UUID tenant = salao();
        lavatoriosEm(tenant, 2);

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> cadastrarRecurso.executar("lavatório", 1)));

        assertThat(codigoDe(erro)).isEqualTo("ER-CAT-RECURSO_NOME_JA_CADASTRADO");
    }

    @Test
    @DisplayName("dois salões podem ter cada um o seu 'Lavatório'")
    void mesmo_nome_em_saloes_diferentes() {
        UUID salaoA = salao();
        UUID salaoB = salao();

        assertThatCode(() -> {
            lavatoriosEm(salaoA, 2);
            lavatoriosEm(salaoB, 4);
        }).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------ a exigência por bloco

    @Test
    @DisplayName("a coloração exige o lavatório SÓ na lavagem")
    void exigencia_mora_no_bloco() {
        // É a rotina inteira. Exigir o lavatório no serviço reservaria o equipamento pelos 100
        // minutos, quando ele é usado em 30 — o mesmo desperdício que os blocos ATIVO/PAUSA
        // existem para eliminar, movido para a outra coisa escassa.
        UUID tenant = salao();
        UUID lavatorio = lavatoriosEm(tenant, 2).id();
        UUID coloracao = coloracaoEm(tenant, lavatorio).id();

        var lido = TenantContext.obter(tenant, () -> catalogo.porId(coloracao)).orElseThrow();

        assertThat(lido.blocos().blocos().get(0).recursosExigidos())
                .as("aplicar a tinta não usa o lavatório").isEmpty();
        assertThat(lido.blocos().blocos().get(1).recursosExigidos())
                .as("a química agindo, tampouco").isEmpty();
        assertThat(lido.blocos().blocos().get(2).recursosExigidos())
                .as("a lavagem, sim").containsExactly(lavatorio);
    }

    @Test
    @DisplayName("um bloco PAUSA pode exigir recurso: a cliente segue na cadeira")
    void pausa_tambem_ocupa() {
        // O caso que um modelo ingênuo erra: durante a química a profissional está livre e a
        // cadeira não. Não saber dizer isso é vender a mesma cadeira duas vezes.
        UUID tenant = salao();
        UUID cadeira = TenantContext.obter(tenant,
                () -> cadastrarRecurso.executar("Cadeira", 4)).id();

        var coloracao = TenantContext.obter(tenant, () -> cadastrarServico.executar(
                "Coloração", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 30, Set.of(cadeira)),
                        new EtapaBruta(TipoDeBloco.PAUSA, 40, Set.of(cadeira)),
                        new EtapaBruta(TipoDeBloco.ATIVO, 30, Set.of(cadeira)))));

        var lido = TenantContext.obter(tenant, () -> catalogo.porId(coloracao.id())).orElseThrow();

        assertThat(lido.minutosDoProfissional()).as("a profissional segue livre na pausa")
                .isEqualTo(60);
        assertThat(lido.blocos().blocos().get(1).recursosExigidos())
                .as("a cadeira, não").containsExactly(cadeira);
    }

    @Test
    @DisplayName("a listagem traz os recursos de cada bloco sem uma consulta por serviço")
    void listagem_traz_os_recursos() {
        UUID tenant = salao();
        UUID lavatorio = lavatoriosEm(tenant, 2).id();
        coloracaoEm(tenant, lavatorio);
        TenantContext.executar(tenant, () -> cadastrarServico.executar("Escova", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 45))));

        var cardapio = TenantContext.obter(tenant, () -> listarRecursos.executar(false));
        var servicos = TenantContext.obter(tenant, () -> catalogo.ativos());

        assertThat(cardapio).hasSize(1);
        assertThat(servicos).hasSize(2);
        assertThat(servicos).filteredOn(s -> s.nome().equals("Coloração"))
                .singleElement()
                .satisfies(s -> assertThat(s.blocos().recursosExigidos())
                        .containsExactly(lavatorio));
        assertThat(servicos).filteredOn(s -> s.nome().equals("Escova"))
                .singleElement()
                .satisfies(s -> assertThat(s.blocos().recursosExigidos()).isEmpty());
    }

    @Test
    @DisplayName("exigir recurso que não existe é 422")
    void recurso_inexistente() {
        UUID tenant = salao();

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> coloracaoEm(tenant, UUID.randomUUID())));

        assertThat(codigoDe(erro)).isEqualTo("ER-CAT-RECURSO_NAO_ENCONTRADO");
    }

    @Test
    @DisplayName("recurso desativado não pode ser exigido por uma receita nova")
    void recurso_inativo_nao_serve() {
        UUID tenant = salao();
        UUID lavatorio = lavatoriosEm(tenant, 2).id();
        TenantContext.executar(tenant, () -> definirAtivacaoDoRecurso.executar(lavatorio, false));

        Throwable erro = catchThrowable(() -> coloracaoEm(tenant, lavatorio));

        assertThat(codigoDe(erro)).isEqualTo("ER-CAT-RECURSO_NAO_ENCONTRADO");
    }

    // ------------------------------------------------------------------ a barreira de tenant

    @Test
    @DisplayName("recurso de outro salão não serve — e quem recusa é o caso de uso")
    void recurso_de_outro_salao_nao_serve() {
        UUID salaoA = salao();
        UUID salaoB = salao();
        UUID lavatorioDoB = lavatoriosEm(salaoB, 2).id();

        Throwable erro = catchThrowable(() -> coloracaoEm(salaoA, lavatorioDoB));

        assertThat(codigoDe(erro)).isEqualTo("ER-CAT-RECURSO_NAO_ENCONTRADO");
    }

    @Test
    @DisplayName("a chave estrangeira SOZINHA não separa os salões — por isso a checagem existe")
    void a_fk_sozinha_nao_e_barreira_de_tenant() throws SQLException {
        // Este teste documenta um comportamento do PostgreSQL que é fácil de supor ao contrário:
        // as checagens de integridade referencial rodam como dono da tabela e NÃO passam pela
        // RLS. A FK de `bloco_exige_recurso` aceita, sem reclamar, um recurso de outro salão.
        //
        // Se um dia deixar de aceitar, este teste falha — e a falha é a notícia boa de que a
        // checagem do caso de uso virou redundância. Enquanto ele passar, ela é a única barreira.
        UUID salaoA = salao();
        UUID salaoB = salao();
        UUID lavatorioDoB = lavatoriosEm(salaoB, 2).id();
        var escovaDoA = TenantContext.obter(salaoA, () -> cadastrarServico.executar(
                "Escova", null, List.of(new EtapaBruta(TipoDeBloco.ATIVO, 45))));
        UUID blocoDoA = ultimoBlocoDe(escovaDoA.id());

        var conexao = comoApp(salaoA);
        try (var ps = conexao.prepareStatement("""
                insert into bloco_exige_recurso (estabelecimento_id, bloco_id, recurso_id)
                values (?, ?, ?)
                """)) {
            ps.setObject(1, salaoA);
            ps.setObject(2, blocoDoA);
            ps.setObject(3, lavatorioDoB);

            assertThatCode(ps::executeUpdate)
                    .as("a RLS protege a linha inserida, não o alvo da chave estrangeira")
                    .doesNotThrowAnyException();
        } finally {
            conexao.rollback();
        }
    }

    // ------------------------------------------------------------------ aposentar

    @Test
    @DisplayName("não desativa recurso que uma receita ativa exige — e o erro diz quais")
    void nao_desativa_recurso_em_uso() {
        // Se passasse, a coloração ficaria impossível de marcar: a agenda procuraria um lavatório
        // livre, não acharia nenhum, e deixaria de oferecer horário. Sem erro e sem explicação.
        UUID tenant = salao();
        UUID lavatorio = lavatoriosEm(tenant, 2).id();
        coloracaoEm(tenant, lavatorio);

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> definirAtivacaoDoRecurso.executar(lavatorio, false)));

        assertThat(codigoDe(erro)).isEqualTo("ER-CAT-RECURSO_EM_USO");
        assertThat(erro).hasMessageContaining("Coloração");
        assertThat(TenantContext.obter(tenant, () -> recursos.porId(lavatorio)).orElseThrow()
                .ativo()).isTrue();
    }

    @Test
    @DisplayName("desativa depois que o serviço sai do cardápio")
    void desativa_quando_ninguem_mais_exige() {
        // A ordem das duas operações é a ordem em que as coisas acontecem na vida: primeiro o
        // salão para de vender coloração, depois se desfaz do lavatório.
        UUID tenant = salao();
        UUID lavatorio = lavatoriosEm(tenant, 2).id();
        UUID coloracao = coloracaoEm(tenant, lavatorio).id();

        TenantContext.executar(tenant, () -> definirAtivacaoDoServico.executar(coloracao, false));
        var depois = TenantContext.obter(tenant,
                () -> definirAtivacaoDoRecurso.executar(lavatorio, false));

        assertThat(depois.ativo()).isFalse();
        assertThat(TenantContext.obter(tenant, () -> listarRecursos.executar(false))).isEmpty();
        assertThat(TenantContext.obter(tenant, () -> listarRecursos.executar(true))).hasSize(1);
    }

    // ------------------------------------------------------------------ edição

    @Test
    @DisplayName("o terceiro lavatório chegou: é uma edição, não um cadastro")
    void aumenta_e_diminui_a_quantidade() {
        UUID tenant = salao();
        Recurso lavatorio = lavatoriosEm(tenant, 2);

        var maior = TenantContext.obter(tenant, () -> atualizarRecurso.executar(
                lavatorio.id(), lavatorio.versao(), "Lavatório", 3));
        assertThat(maior.quantidade()).isEqualTo(3);

        // Diminuir também vale: um lavatório quebrou, e a agenda precisa encolher junto.
        var menor = TenantContext.obter(tenant, () -> atualizarRecurso.executar(
                lavatorio.id(), maior.versao(), "Lavatório", 1));
        assertThat(menor.quantidade()).isEqualTo(1);
    }

    @Test
    @DisplayName("editar com versão velha é 409, não sobrescrita silenciosa")
    void versao_desatualizada() {
        UUID tenant = salao();
        Recurso lavatorio = lavatoriosEm(tenant, 2);
        TenantContext.executar(tenant, () -> atualizarRecurso.executar(
                lavatorio.id(), lavatorio.versao(), "Lavatório", 3));

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> atualizarRecurso.executar(lavatorio.id(), lavatorio.versao(),
                        "Lavatório", 5)));

        assertThat(codigoDe(erro)).isEqualTo("ER-INF-VERSAO_DESATUALIZADA");
        assertThat(TenantContext.obter(tenant, () -> recursos.porId(lavatorio.id()))
                .orElseThrow().quantidade()).as("a edição perdida não passou").isEqualTo(3);
    }

    @Test
    @DisplayName("editar para quantidade zero é 422, não 500")
    void edicao_para_zero_e_422() {
        UUID tenant = salao();
        Recurso lavatorio = lavatoriosEm(tenant, 2);

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> atualizarRecurso.executar(lavatorio.id(), lavatorio.versao(),
                        "Lavatório", 0)));

        assertThat(codigoDe(erro)).isEqualTo("ER-CAT-DADOS_INVALIDOS");
    }

    // ------------------------------------------------------------------ isolamento e permissão

    @Test
    @DisplayName("um salão não vê os recursos do outro")
    void isolamento_entre_saloes() {
        UUID salaoA = salao();
        UUID salaoB = salao();
        Recurso daA = lavatoriosEm(salaoA, 3);

        assertThat(TenantContext.obter(salaoB, () -> listarRecursos.executar(true))).isEmpty();
        assertThat(TenantContext.obter(salaoB, () -> recursos.porId(daA.id()))).isEmpty();
    }

    @Test
    @DisplayName("a recepção lê os recursos mas não os altera")
    @WithMockUser(authorities = Permissao.AGENDA_LER_TODAS)
    void recepcao_le_mas_nao_altera() {
        // Ler é necessário: "os dois lavatórios estão ocupados" só explica o horário indisponível
        // se a recepção souber que existem dois. Alterar muda a capacidade do salão inteiro.
        UUID tenant = salao();

        assertThat(TenantContext.obter(tenant, () -> listarRecursos.executar(false))).isEmpty();
        assertThatThrownBy(() -> TenantContext.executar(tenant,
                () -> cadastrarRecurso.executar("Lavatório", 2)))
                .isInstanceOf(AccessDeniedException.class);
    }
}
