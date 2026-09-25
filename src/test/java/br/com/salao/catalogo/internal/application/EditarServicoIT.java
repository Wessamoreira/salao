package br.com.salao.catalogo.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.catalogo.api.ServicoResumo;
import br.com.salao.catalogo.api.TipoDeBloco;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-CAT-005 — corrigir um serviço que já existe. */
@WithMockUser(authorities = { Permissao.CONFIG_GERENCIAR, Permissao.AGENDA_LER_TODAS })
class EditarServicoIT extends AbstractPostgresIT {

    @Autowired
    private ProvisionarEstabelecimentoUseCase provisionar;

    @Autowired
    private CadastrarServicoUseCase cadastrar;

    @Autowired
    private EditarServicoUseCase editar;

    @Autowired
    private CadastrarRecursoUseCase cadastrarRecurso;

    private UUID salao() {
        return provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Ana",
                "ana-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
    }

    private EtapaBruta ativo(int minutos) {
        return new EtapaBruta(TipoDeBloco.ATIVO, minutos);
    }

    private EtapaBruta pausa(int minutos) {
        return new EtapaBruta(TipoDeBloco.PAUSA, minutos);
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    private ServicoResumo coloracao(UUID tenant) {
        return TenantContext.obter(tenant, () -> cadastrar.executar("Coloração",
                "raiz e comprimento", List.of(ativo(30), pausa(40), ativo(30))));
    }

    @Test
    @DisplayName("trocar a receita troca a duração e sobe a versão")
    void edita_a_receita() {
        // O caso que motivou a rotina: a química agia 40 minutos e passou a agir 45. Sem isto, a
        // saída seria cadastrar outro serviço e aposentar o velho — o histórico da agenda ficaria
        // dividido entre dois cardápios com o mesmo nome.
        UUID tenant = salao();
        var antes = coloracao(tenant);

        var depois = TenantContext.obter(tenant, () -> editar.executar(antes.id(), "Coloração",
                "raiz e comprimento", List.of(ativo(30), pausa(45), ativo(30)), antes.versao()));

        assertThat(depois.duracaoTotal()).isEqualTo(105);
        assertThat(depois.minutosDoProfissional()).isEqualTo(60);
        assertThat(depois.versao()).isGreaterThan(antes.versao());
        assertThat(depois.blocos().blocos()).hasSize(3);
    }

    @Test
    @DisplayName("corrigir o nome não cria serviço novo")
    void corrige_o_nome() {
        UUID tenant = salao();
        var antes = coloracao(tenant);

        var depois = TenantContext.obter(tenant, () -> editar.executar(antes.id(), "Coloração raiz",
                null, List.of(ativo(30), pausa(40), ativo(30)), antes.versao()));

        assertThat(depois.id()).isEqualTo(antes.id());
        assertThat(depois.nome()).isEqualTo("Coloração raiz");
        assertThat(depois.descricao()).isNull();
    }

    @Test
    @DisplayName("receita inválida é recusada com código estável, não com 500")
    void recusa_receita_invalida() {
        // Terminar em PAUSA (RN-CAT-002) é o erro mais fácil de cometer montando a receita na
        // tela: adiciona-se a espera e esquece-se a finalização.
        UUID tenant = salao();
        var servico = coloracao(tenant);

        Throwable erro = catchThrowable(() -> TenantContext.obter(tenant,
                () -> editar.executar(servico.id(), "Coloração", null,
                        List.of(ativo(30), pausa(40)), servico.versao())));

        assertThat(codigoDe(erro)).isEqualTo("ER-CAT-SEQUENCIA_INVALIDA");
    }

    @Test
    @DisplayName("a receita antiga continua valendo quando a gravação é recusada")
    void recusa_nao_deixa_receita_pela_metade() {
        // A sequência é apagada e regravada: uma recusa no meio deixaria o serviço sem blocos, e
        // a agenda deixaria de oferecer o horário dele sem ninguém entender por quê.
        UUID tenant = salao();
        var servico = coloracao(tenant);

        catchThrowable(() -> TenantContext.obter(tenant, () -> editar.executar(servico.id(),
                "Coloração", null, List.of(ativo(30), pausa(40)), servico.versao())));

        var agora = TenantContext.obter(tenant, () -> editar.executar(servico.id(), "Coloração",
                null, List.of(ativo(30), pausa(40), ativo(30)), servico.versao()));
        assertThat(agora.duracaoTotal()).isEqualTo(100);
    }

    @Test
    @DisplayName("versão velha perde para quem salvou antes, em vez de apagar a correção dele")
    void conflito_de_versao() {
        UUID tenant = salao();
        var servico = coloracao(tenant);
        // Duas telas leram a mesma versão. A primeira grava.
        TenantContext.obter(tenant, () -> editar.executar(servico.id(), "Coloração",
                "corrigido pela primeira", List.of(ativo(30), pausa(40), ativo(30)),
                servico.versao()));

        Throwable erro = catchThrowable(() -> TenantContext.obter(tenant,
                () -> editar.executar(servico.id(), "Coloração", "corrigido pela segunda",
                        List.of(ativo(30), pausa(40), ativo(30)), servico.versao())));

        assertThat(codigoDe(erro)).isEqualTo("ER-CAT-CONFLITO_DE_VERSAO");
    }

    @Test
    @DisplayName("recurso de outro salão não entra na receita")
    void recurso_de_outro_salao() {
        // A FK de bloco_exige_recurso roda como dona da tabela e NÃO passa pela RLS: quem separa
        // os salões aqui é a consulta do caso de uso (RN-CAT-011).
        UUID meu = salao();
        UUID outro = salao();
        var doOutro = TenantContext.obter(outro, () -> cadastrarRecurso.executar("Lavatório", 2));
        var servico = coloracao(meu);

        Throwable erro = catchThrowable(() -> TenantContext.obter(meu,
                () -> editar.executar(servico.id(), "Coloração", null,
                        List.of(new EtapaBruta(TipoDeBloco.ATIVO, 30, Set.of(doOutro.id())),
                                pausa(40), ativo(30)),
                        servico.versao())));

        assertThat(codigoDe(erro)).isEqualTo("ER-CAT-RECURSO_NAO_ENCONTRADO");
    }

    @Test
    @DisplayName("serviço de outro salão não é encontrado")
    void servico_de_outro_salao() {
        UUID meu = salao();
        UUID outro = salao();
        var doOutro = coloracao(outro);

        Throwable erro = catchThrowable(() -> TenantContext.obter(meu,
                () -> editar.executar(doOutro.id(), "Coloração", null,
                        List.of(ativo(30), pausa(40), ativo(30)), doOutro.versao())));

        assertThat(codigoDe(erro)).isEqualTo("ER-CAT-SERVICO_NAO_ENCONTRADO");
    }

    @Test
    @WithMockUser(authorities = { Permissao.AGENDA_LER_TODAS })
    @DisplayName("quem só lê a agenda não edita o cardápio")
    void so_o_dono_edita() {
        // RN-CAT-004: mudar a duração de um serviço muda a agenda de todo mundo. Não é operação
        // de balcão.
        UUID tenant = salao();

        Throwable erro = catchThrowable(() -> TenantContext.obter(tenant,
                () -> editar.executar(UUID.randomUUID(), "Coloração", null,
                        List.of(ativo(30)), 0)));

        assertThat(erro).isInstanceOf(AccessDeniedException.class);
    }
}
