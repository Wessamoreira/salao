package br.com.salao.equipe.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.catalogo.api.TipoDeBloco;
import br.com.salao.catalogo.internal.application.CadastrarServicoUseCase;
import br.com.salao.catalogo.internal.application.DefinirAtivacaoDoServicoUseCase;
import br.com.salao.catalogo.internal.application.EtapaBruta;
import br.com.salao.equipe.api.Habilidades;
import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.equipe.api.ProfissionalResumo;
import br.com.salao.equipe.api.TipoDeVinculo;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-EQP-004 — quais serviços cada profissional executa. */
@WithMockUser(authorities = { Permissao.USUARIO_GERENCIAR, Permissao.CONFIG_GERENCIAR,
        Permissao.AGENDA_LER_TODAS })
class HabilidadesIT extends AbstractPostgresIT {

    @Autowired
    private ProvisionarEstabelecimentoUseCase provisionar;

    @Autowired
    private CadastrarProfissionalUseCase cadastrarProfissional;

    @Autowired
    private DefinirAtivacaoDoProfissionalUseCase definirAtivacaoDoProfissional;

    @Autowired
    private DefinirHabilidadesUseCase definirHabilidades;

    @Autowired
    private CadastrarServicoUseCase cadastrarServico;

    @Autowired
    private DefinirAtivacaoDoServicoUseCase definirAtivacaoDoServico;

    @Autowired
    private ProfissionalApi equipe;

    private UUID salao() {
        return provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
    }

    /** CPFs válidos de verdade — dígito conferido, como manda RN-EQP-001. */
    private static final String[] CPFS = { "11144477735", "52998224725", "87748248800" };

    private UUID profissional(UUID tenant, String nome, int qualCpf) {
        return TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional(nome, null, CPFS[qualCpf], TipoDeVinculo.EMPREGADO,
                        null, null, null, null, null, null))).id();
    }

    private UUID servico(UUID tenant, String nome) {
        return TenantContext.obter(tenant, () -> cadastrarServico.executar(nome, null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 45)))).id();
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ os dois regimes

    @Test
    @DisplayName("profissional recém-cadastrado já faz o cardápio inteiro")
    void nasce_no_regime_aberto() {
        // É o que faz um salão novo funcionar no primeiro dia. Com lista branca pura, seriam
        // 6 profissionais x 40 serviços de caixas antes do primeiro agendamento.
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID coloracao = servico(tenant, "Coloração");

        assertThat(TenantContext.obter(tenant, () -> equipe.executa(ana, coloracao))).isTrue();
        assertThat(TenantContext.obter(tenant, () -> equipe.habilidadesDe(ana).executaTodos()))
                .isTrue();
    }

    @Test
    @DisplayName("no regime aberto, serviço NOVO já nasce disponível para quem já existia")
    void servico_novo_alcanca_quem_ja_existia() {
        // O defeito silencioso que a lista branca pura produziria: "por que a coloração nova não
        // aparece na agenda?", sem erro e sem ninguém saber onde olhar.
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);

        UUID servicoNovo = servico(tenant, "Botox capilar");

        assertThat(TenantContext.obter(tenant, () -> equipe.executa(ana, servicoNovo))).isTrue();
    }

    @Test
    @DisplayName("restringir faz o profissional executar só o que está na lista")
    void regime_restrito() {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID coloracao = servico(tenant, "Coloração");
        UUID manicure = servico(tenant, "Manicure");

        TenantContext.executar(tenant,
                () -> definirHabilidades.executar(ana, false, Set.of(manicure), Map.of()));

        assertThat(TenantContext.obter(tenant, () -> equipe.executa(ana, manicure))).isTrue();
        assertThat(TenantContext.obter(tenant, () -> equipe.executa(ana, coloracao)))
                .as("progressiva com quem só faz manicure é o defeito da RN-AGD-001").isFalse();
    }

    @Test
    @DisplayName("depois de restringir, serviço novo NÃO alcança quem está restrito")
    void restrito_nao_recebe_servico_novo() {
        // A consequência de virar a chave, e ela precisa estar num teste: é o que a tela tem de
        // avisar para o salão não descobrir sozinho.
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID manicure = servico(tenant, "Manicure");
        TenantContext.executar(tenant,
                () -> definirHabilidades.executar(ana, false, Set.of(manicure), Map.of()));

        UUID servicoNovo = servico(tenant, "Botox capilar");

        assertThat(TenantContext.obter(tenant, () -> equipe.executa(ana, servicoNovo))).isFalse();
    }

    @Test
    @DisplayName("voltar ao regime aberto descarta a lista")
    void volta_ao_regime_aberto() {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID manicure = servico(tenant, "Manicure");
        UUID coloracao = servico(tenant, "Coloração");
        TenantContext.executar(tenant,
                () -> definirHabilidades.executar(ana, false, Set.of(manicure), Map.of()));

        var voltou = TenantContext.obter(tenant,
                () -> definirHabilidades.executar(ana, true, Set.of(), Map.of()));

        assertThat(voltou.executaTodos()).isTrue();
        assertThat(voltou.servicos()).isEmpty();
        assertThat(TenantContext.obter(tenant, () -> equipe.executa(ana, coloracao))).isTrue();
    }

    @Test
    @DisplayName("restringir a nenhum serviço é 422, não 500")
    void restrito_e_vazio_e_422() {
        // Sétima ocorrência da família: o domínio recusa com IllegalArgumentException, e sem
        // tradutor isso seria 500. Desta vez o tradutor entrou junto com o código.
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> definirHabilidades.executar(ana, false, Set.of(), Map.of())));

        assertThat(erro).isInstanceOf(ErroDeDominio.class);
        assertThat(codigoDe(erro)).isEqualTo("ER-EQP-HABILIDADES_VAZIAS");
        assertThat(TenantContext.obter(tenant, () -> equipe.habilidadesDe(ana).executaTodos()))
                .as("a recusa não deixou estado pela metade").isTrue();
    }

    // ------------------------------------------------------------------ a barreira sem FK

    @Test
    @DisplayName("serviço de outro salão não vira habilidade — e não há FK para impedir")
    void servico_de_outro_salao_nao_serve() {
        // `habilidade` não tem chave estrangeira para `servico`: ela costuraria dois módulos. E,
        // como mostra RN-CAT-011, nem se tivesse — as checagens de integridade referencial do
        // PostgreSQL não passam pela RLS. Esta consulta ao ServicoApi é a única barreira.
        UUID salaoA = salao();
        UUID salaoB = salao();
        UUID ana = profissional(salaoA, "Ana", 0);
        UUID coloracaoDoB = servico(salaoB, "Coloração");

        Throwable erro = catchThrowable(() -> TenantContext.executar(salaoA,
                () -> definirHabilidades.executar(ana, false, Set.of(coloracaoDoB), Map.of())));

        assertThat(codigoDe(erro)).isEqualTo("ER-EQP-SERVICO_NAO_ENCONTRADO");
    }

    @Test
    @DisplayName("serviço inexistente é 422")
    void servico_inexistente() {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> definirHabilidades.executar(ana, false, Set.of(UUID.randomUUID()), Map.of())));

        assertThat(codigoDe(erro)).isEqualTo("ER-EQP-SERVICO_NAO_ENCONTRADO");
    }

    @Test
    @DisplayName("serviço fora do cardápio ainda pode ser habilidade")
    void servico_desativado_ainda_serve() {
        // De propósito: um serviço desativado pode voltar, e recusar a habilidade dele faria a
        // informação de quem sabe fazê-lo se perder no caminho.
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID coloracao = servico(tenant, "Coloração");
        TenantContext.executar(tenant,
                () -> definirAtivacaoDoServico.executar(coloracao, false));

        var habilidades = TenantContext.obter(tenant,
                () -> definirHabilidades.executar(ana, false, Set.of(coloracao), Map.of()));

        assertThat(habilidades.servicos()).containsExactly(coloracao);
    }

    // ------------------------------------------------------------------ quem pode fazer

    @Test
    @DisplayName("quem pode fazer o serviço inclui os do regime aberto e os da lista")
    void quem_executa_o_servico() {
        // A consulta da tela de marcação. Se ela só olhasse a tabela, o salão inteiro sumiria da
        // lista — porque no primeiro dia ninguém tem linha nenhuma.
        UUID tenant = salao();
        UUID coloracao = servico(tenant, "Coloração");
        UUID manicure = servico(tenant, "Manicure");
        UUID ana = profissional(tenant, "Ana", 0);
        UUID julia = profissional(tenant, "Júlia", 1);
        TenantContext.executar(tenant,
                () -> definirHabilidades.executar(julia, false, Set.of(manicure), Map.of()));

        var podemColorir = TenantContext.obter(tenant, () -> equipe.queExecutam(coloracao));
        var podemFazerUnha = TenantContext.obter(tenant, () -> equipe.queExecutam(manicure));

        assertThat(podemColorir).extracting(ProfissionalResumo::id).containsExactly(ana);
        assertThat(podemFazerUnha).extracting(ProfissionalResumo::nome)
                .containsExactlyInAnyOrder("Ana", "Júlia");
    }

    @Test
    @DisplayName("profissional inativo não executa nada, mesmo no regime aberto")
    void inativo_nao_executa() {
        // A checagem mora na consulta e não em quem chama: deixar as duas separadas seria
        // convidar algum chamador a esquecer uma delas.
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID coloracao = servico(tenant, "Coloração");
        TenantContext.executar(tenant,
                () -> definirAtivacaoDoProfissional.executar(ana, false));

        assertThat(TenantContext.obter(tenant, () -> equipe.executa(ana, coloracao))).isFalse();
        assertThat(TenantContext.obter(tenant, () -> equipe.queExecutam(coloracao))).isEmpty();
    }

    @Test
    @DisplayName("profissional inexistente não executa, e não estoura")
    void inexistente_nao_executa() {
        UUID tenant = salao();
        UUID coloracao = servico(tenant, "Coloração");

        assertThat(TenantContext.obter(tenant,
                () -> equipe.executa(UUID.randomUUID(), coloracao))).isFalse();
    }

    // ------------------------------------------------------------------ isolamento e permissão

    @Test
    @DisplayName("um salão não vê as habilidades do outro")
    void isolamento_entre_saloes() {
        UUID salaoA = salao();
        UUID salaoB = salao();
        UUID ana = profissional(salaoA, "Ana", 0);
        UUID manicure = servico(salaoA, "Manicure");
        TenantContext.executar(salaoA,
                () -> definirHabilidades.executar(ana, false, Set.of(manicure), Map.of()));

        assertThat(TenantContext.obter(salaoB, () -> equipe.executa(ana, manicure)))
                .as("do salão B, a profissional do A nem existe").isFalse();
        assertThat(TenantContext.obter(salaoB, () -> equipe.queExecutam(manicure))).isEmpty();
    }

    @Test
    @DisplayName("a recepção não define habilidades")
    @WithMockUser(authorities = Permissao.AGENDA_LER_TODAS)
    void recepcao_nao_altera() {
        // Mudar quem faz o quê muda o que a agenda oferece para o salão inteiro.
        UUID tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));

        assertThatThrownBy(() -> TenantContext.executar(tenant,
                () -> definirHabilidades.executar(UUID.randomUUID(), true, Set.of(), Map.of())))
                .isInstanceOf(AccessDeniedException.class);
    }
}
