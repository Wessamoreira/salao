package br.com.salao.equipe.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.catalogo.api.SequenciaDeBlocos;
import br.com.salao.catalogo.api.TipoDeBloco;
import br.com.salao.catalogo.internal.application.CadastrarServicoUseCase;
import br.com.salao.catalogo.internal.application.EtapaBruta;
import br.com.salao.equipe.api.ProfissionalApi;
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
import org.springframework.security.test.context.support.WithMockUser;

/** RT-CAT-004 — quanto tempo cada profissional leva. */
@WithMockUser(authorities = { Permissao.USUARIO_GERENCIAR, Permissao.CONFIG_GERENCIAR,
        Permissao.AGENDA_LER_TODAS })
class DuracaoPorProfissionalIT extends AbstractPostgresIT {

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
    private ProfissionalApi equipe;

    private static final String[] CPFS = { "11144477735", "52998224725" };

    private UUID salao() {
        return provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
    }

    private UUID profissional(UUID tenant, String nome, int qualCpf) {
        return TenantContext.obter(tenant, () -> cadastrarProfissional.executar(
                new DadosDoProfissional(nome, null, CPFS[qualCpf], TipoDeVinculo.EMPREGADO,
                        null, null, null, null, null, null))).id();
    }

    private UUID escova(UUID tenant) {
        return TenantContext.obter(tenant, () -> cadastrarServico.executar("Escova", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 45)))).id();
    }

    /** Coloração de verdade: aplica 30, química age 40, lava e finaliza 30. */
    private UUID coloracao(UUID tenant) {
        return TenantContext.obter(tenant, () -> cadastrarServico.executar("Coloração", null,
                List.of(new EtapaBruta(TipoDeBloco.ATIVO, 30),
                        new EtapaBruta(TipoDeBloco.PAUSA, 40),
                        new EtapaBruta(TipoDeBloco.ATIVO, 30)))).id();
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ------------------------------------------------------------------ o caso da rotina

    @Test
    @DisplayName("a Ana faz escova em 30 e a Júlia em 45")
    void cada_uma_no_seu_tempo() {
        // Hoje o sistema prometeria o mesmo horário para as duas: ou a agenda da Ana fica com
        // buraco de 15 minutos em cada escova, ou a da Júlia atrasa 15 e o atraso acumula.
        UUID tenant = salao();
        UUID escova = escova(tenant);
        UUID ana = profissional(tenant, "Ana", 0);
        UUID julia = profissional(tenant, "Júlia", 1);

        TenantContext.executar(tenant, () -> definirHabilidades.executar(
                ana, true, Set.of(), Map.of(escova, 30)));

        assertThat(TenantContext.obter(tenant, () -> equipe.receitaDe(ana, escova)))
                .get().extracting(SequenciaDeBlocos::duracaoTotal).isEqualTo(30);
        assertThat(TenantContext.obter(tenant, () -> equipe.receitaDe(julia, escova)))
                .as("sem duração própria, o tempo do catálogo")
                .get().extracting(SequenciaDeBlocos::duracaoTotal).isEqualTo(45);
    }

    @Test
    @DisplayName("a química continua levando 40 minutos, por mais rápida que a Ana seja")
    void a_pausa_nao_encolhe() {
        // A decisão da rotina. Encolher a pausa junto mandaria lavar o cabelo antes da hora.
        UUID tenant = salao();
        UUID coloracao = coloracao(tenant);
        UUID ana = profissional(tenant, "Ana", 0);

        TenantContext.executar(tenant, () -> definirHabilidades.executar(
                ana, true, Set.of(), Map.of(coloracao, 90)));

        var receita = TenantContext.obter(tenant,
                () -> equipe.receitaDe(ana, coloracao)).orElseThrow();

        assertThat(receita.duracaoTotal()).isEqualTo(90);
        assertThat(receita.minutosDePausa()).as("a química, intocada").isEqualTo(40);
        assertThat(receita.minutosDoProfissional()).as("o trabalho, de 60 para 50").isEqualTo(50);
    }

    @Test
    @DisplayName("a duração SOBREVIVE a uma edição de habilidade")
    void duracao_sobrevive_a_edicao() {
        // O defeito que essa rotina quase teve: `substituirHabilidades` apaga e regrava as linhas,
        // então uma duração guardada numa tabela irmã sumiria na próxima edição, em silêncio. É a
        // mesma lição de RT-CAT-003 sobre o recurso do bloco.
        UUID tenant = salao();
        UUID escova = escova(tenant);
        UUID coloracao = coloracao(tenant);
        UUID ana = profissional(tenant, "Ana", 0);
        TenantContext.executar(tenant, () -> definirHabilidades.executar(
                ana, false, Set.of(escova), Map.of(escova, 30)));

        // A tela manda o formulário inteiro: a lista cresceu e a duração veio junto.
        TenantContext.executar(tenant, () -> definirHabilidades.executar(
                ana, false, Set.of(escova, coloracao), Map.of(escova, 30)));

        assertThat(TenantContext.obter(tenant,
                () -> equipe.habilidadesDe(ana).duracaoDe(escova))).contains(30);
        assertThat(TenantContext.obter(tenant, () -> equipe.receitaDe(ana, escova)))
                .get().extracting(SequenciaDeBlocos::duracaoTotal).isEqualTo(30);
    }

    @Test
    @DisplayName("tirar a duração devolve o profissional ao tempo do catálogo")
    void remover_a_duracao() {
        UUID tenant = salao();
        UUID escova = escova(tenant);
        UUID ana = profissional(tenant, "Ana", 0);
        TenantContext.executar(tenant, () -> definirHabilidades.executar(
                ana, true, Set.of(), Map.of(escova, 30)));

        TenantContext.executar(tenant,
                () -> definirHabilidades.executar(ana, true, Set.of(), Map.of()));

        assertThat(TenantContext.obter(tenant,
                () -> equipe.habilidadesDe(ana).duracaoDe(escova))).isEmpty();
        assertThat(TenantContext.obter(tenant, () -> equipe.receitaDe(ana, escova)))
                .get().extracting(SequenciaDeBlocos::duracaoTotal).isEqualTo(45);
    }

    // ------------------------------------------------------------------ recusas

    @Test
    @DisplayName("duração que não deixa espaço para o trabalho é 422, não 500")
    void duracao_menor_que_a_pausa() {
        // Dizer que alguém faz a coloração em 40 minutos é dizer que ela aplica, espera a química
        // agir e finaliza no tempo em que só a química acontece.
        UUID tenant = salao();
        UUID coloracao = coloracao(tenant);
        UUID ana = profissional(tenant, "Ana", 0);

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> definirHabilidades.executar(ana, true, Set.of(), Map.of(coloracao, 40))));

        assertThat(erro).isInstanceOf(ErroDeDominio.class);
        assertThat(codigoDe(erro)).isEqualTo("ER-EQP-DURACAO_NAO_CABE");
        assertThat(erro).hasMessageContaining("Coloração");
    }

    @Test
    @DisplayName("duração para serviço que a pessoa não executa é 422")
    void duracao_sem_habilidade() {
        UUID tenant = salao();
        UUID escova = escova(tenant);
        UUID coloracao = coloracao(tenant);
        UUID ana = profissional(tenant, "Ana", 0);

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> definirHabilidades.executar(ana, false, Set.of(escova),
                        Map.of(coloracao, 90))));

        assertThat(codigoDe(erro)).isEqualTo("ER-EQP-DURACAO_SEM_HABILIDADE");
    }

    @Test
    @DisplayName("duração para serviço de outro salão é 422")
    void duracao_de_servico_alheio() {
        UUID salaoA = salao();
        UUID salaoB = salao();
        UUID escovaDoB = escova(salaoB);
        UUID ana = profissional(salaoA, "Ana", 0);

        Throwable erro = catchThrowable(() -> TenantContext.executar(salaoA,
                () -> definirHabilidades.executar(ana, true, Set.of(), Map.of(escovaDoB, 30))));

        assertThat(codigoDe(erro)).isEqualTo("ER-EQP-SERVICO_NAO_ENCONTRADO");
    }

    @Test
    @DisplayName("duração zero é 422")
    void duracao_zero() {
        UUID tenant = salao();
        UUID escova = escova(tenant);
        UUID ana = profissional(tenant, "Ana", 0);

        Throwable erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> definirHabilidades.executar(ana, true, Set.of(), Map.of(escova, 0))));

        assertThat(codigoDe(erro)).isEqualTo("ER-EQP-HABILIDADES_VAZIAS");
    }

    // ------------------------------------------------------------------ a pergunta única

    @Test
    @DisplayName("quem não executa o serviço não tem receita")
    void sem_habilidade_sem_receita() {
        // `receitaDe` responde as duas coisas de uma vez de propósito: dois métodos deixariam
        // algum chamador usar a duração do catálogo para quem leva outra, e o erro não apareceria
        // — a agenda só ficaria errada.
        UUID tenant = salao();
        UUID escova = escova(tenant);
        UUID coloracao = coloracao(tenant);
        UUID ana = profissional(tenant, "Ana", 0);
        TenantContext.executar(tenant,
                () -> definirHabilidades.executar(ana, false, Set.of(escova), Map.of()));

        assertThat(TenantContext.obter(tenant, () -> equipe.receitaDe(ana, coloracao))).isEmpty();
        assertThat(TenantContext.obter(tenant, () -> equipe.receitaDe(ana, escova))).isPresent();
    }

    @Test
    @DisplayName("profissional inativo não tem receita")
    void inativo_sem_receita() {
        UUID tenant = salao();
        UUID escova = escova(tenant);
        UUID ana = profissional(tenant, "Ana", 0);
        TenantContext.executar(tenant, () -> definirHabilidades.executar(
                ana, true, Set.of(), Map.of(escova, 30)));

        TenantContext.executar(tenant, () -> definirAtivacaoDoProfissional.executar(ana, false));

        assertThat(TenantContext.obter(tenant, () -> equipe.receitaDe(ana, escova))).isEmpty();
    }

    @Test
    @DisplayName("serviço inexistente não tem receita, e não estoura")
    void servico_inexistente_sem_receita() {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);

        assertThat(TenantContext.obter(tenant,
                () -> equipe.receitaDe(ana, UUID.randomUUID()))).isEmpty();
    }

    @Test
    @DisplayName("a receita reescalada mantém os recursos exigidos")
    void receita_mantem_recursos() {
        UUID tenant = salao();
        UUID ana = profissional(tenant, "Ana", 0);
        UUID coloracao = coloracao(tenant);
        TenantContext.executar(tenant, () -> definirHabilidades.executar(
                ana, true, Set.of(), Map.of(coloracao, 90)));

        var receita = TenantContext.obter(tenant,
                () -> equipe.receitaDe(ana, coloracao)).orElseThrow();

        assertThat(receita.blocos()).hasSize(3);
        assertThat(receita.blocos().get(1).tipo()).isEqualTo(TipoDeBloco.PAUSA);
    }
}
