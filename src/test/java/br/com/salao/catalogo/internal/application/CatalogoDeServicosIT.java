package br.com.salao.catalogo.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.catalogo.api.ServicoApi;
import br.com.salao.catalogo.api.ServicoResumo;
import br.com.salao.catalogo.api.TipoDeBloco;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-CAT-001 — cardápio de serviços. */
@WithMockUser(authorities = { Permissao.CONFIG_GERENCIAR, Permissao.AGENDA_LER_TODAS })
class CatalogoDeServicosIT extends AbstractPostgresIT {

    @Autowired
    private ProvisionarEstabelecimentoUseCase provisionar;

    @Autowired
    private CadastrarServicoUseCase cadastrar;

    @Autowired
    private ListarServicosUseCase listar;

    @Autowired
    private DefinirAtivacaoDoServicoUseCase definirAtivacao;

    @Autowired
    private ServicoApi catalogo;

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

    @Test
    @DisplayName("coloração separa o tempo da cliente do tempo do profissional")
    void coloracao_com_pausa() {
        // A razão de o modelo existir: 100 minutos de cadeira, 60 de profissional. Os 40 do meio
        // são o horário de outra cliente num sábado.
        UUID tenant = salao();

        var servico = TenantContext.obter(tenant, () -> cadastrar.executar("Coloração",
                "Aplicação, química agindo e finalização",
                List.of(ativo(30), pausa(40), ativo(30))));

        assertThat(servico.duracaoTotal()).isEqualTo(100);
        assertThat(servico.minutosDoProfissional()).isEqualTo(60);
        assertThat(servico.blocos().temPausa()).isTrue();
        assertThat(servico.blocos().blocos()).hasSize(3);
    }

    @Test
    @DisplayName("os blocos voltam do banco na ordem em que foram gravados")
    void ordem_dos_blocos_persiste() {
        // A ordem É a receita: trocar a pausa de lugar muda quando o profissional fica livre.
        UUID tenant = salao();
        var salvo = TenantContext.obter(tenant, () -> cadastrar.executar("Luzes", null,
                List.of(ativo(40), pausa(30), ativo(20), pausa(15), ativo(25))));

        var lido = TenantContext.obter(tenant, () -> catalogo.porId(salvo.id()).orElseThrow());

        assertThat(lido.blocos().blocos()).extracting("duracaoMinutos")
                .containsExactly(40, 30, 20, 15, 25);
        assertThat(lido.minutosDoProfissional()).isEqualTo(85);
    }

    @Test
    @DisplayName("serviço simples é um bloco só")
    void servico_simples() {
        UUID tenant = salao();

        var escova = TenantContext.obter(tenant,
                () -> cadastrar.executar("Escova", null, List.of(ativo(45))));

        assertThat(escova.duracaoTotal()).isEqualTo(45);
        assertThat(escova.minutosDoProfissional()).isEqualTo(45);
        assertThat(escova.blocos().temPausa()).isFalse();
    }

    @Test
    @DisplayName("receita que termina em pausa é recusada — ninguém escalado para lavar")
    void termina_em_pausa_e_recusada() {
        UUID tenant = salao();

        var erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> cadastrar.executar("Errado", null, List.of(ativo(30), pausa(40)))));

        assertThat(codigoDe(erro)).isEqualTo("ER-CAT-SEQUENCIA_INVALIDA");
    }

    @Test
    @DisplayName("blocos do mesmo tipo em sequência são recusados")
    void tipos_repetidos_recusados() {
        UUID tenant = salao();

        var erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> cadastrar.executar("Errado", null, List.of(ativo(30), ativo(20)))));

        assertThat(codigoDe(erro)).isEqualTo("ER-CAT-SEQUENCIA_INVALIDA");
    }

    @Test
    @DisplayName("duração zero vinda de fora é 422, não 500")
    void duracao_zero_e_erro_de_dominio() {
        // Quinta ocorrência da família: exceção que a borda pode provocar precisa de tradutor.
        // Desta vez o tradutor entrou antes de o defeito aparecer.
        UUID tenant = salao();

        var erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> cadastrar.executar("Errado", null, List.of(ativo(0)))));

        assertThat(codigoDe(erro)).isEqualTo("ER-CAT-SEQUENCIA_INVALIDA");
    }

    @Test
    @DisplayName("serviço sem etapa nenhuma é recusado")
    void sem_etapa() {
        UUID tenant = salao();

        var erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> cadastrar.executar("Errado", null, List.of())));

        assertThat(codigoDe(erro)).isEqualTo("ER-CAT-SEQUENCIA_INVALIDA");
    }

    @Test
    @DisplayName("nome repetido é recusado, ignorando a caixa")
    void nome_e_unico() {
        // Dois "Escova" fazem a recepção escolher o errado metade das vezes, e o extrato de
        // comissão fica dividido entre os dois.
        UUID tenant = salao();
        TenantContext.executar(tenant, () -> cadastrar.executar("Escova", null, List.of(ativo(45))));

        var erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> cadastrar.executar("ESCOVA", null, List.of(ativo(30)))));

        assertThat(codigoDe(erro)).isEqualTo("ER-CAT-NOME_JA_CADASTRADO");
    }

    @Test
    @DisplayName("o mesmo nome pode existir em salões diferentes")
    void nome_repetido_entre_saloes() {
        UUID salaoA = salao();
        UUID salaoB = salao();
        TenantContext.executar(salaoA, () -> cadastrar.executar("Escova", null, List.of(ativo(45))));

        var noB = TenantContext.obter(salaoB,
                () -> cadastrar.executar("Escova", null, List.of(ativo(30))));

        assertThat(noB.id()).isNotNull();
    }

    @Test
    @DisplayName("desativar tira do cardápio e mantém o serviço")
    void desativar_mantem_o_servico() {
        // O histórico de atendimento aponta para o serviço; apagar quebraria o relatório do que
        // foi vendido e o extrato de comissão calculado sobre ele.
        UUID tenant = salao();
        var escova = TenantContext.obter(tenant,
                () -> cadastrar.executar("Escova", null, List.of(ativo(45))));

        TenantContext.executar(tenant, () -> definirAtivacao.executar(escova.id(), false));

        assertThat(TenantContext.obter(tenant, () -> catalogo.ativos())).isEmpty();
        assertThat(TenantContext.obter(tenant, () -> catalogo.porId(escova.id()))).isPresent();
        assertThat(TenantContext.obter(tenant, () -> listar.executar(true)))
                .as("quem administra continua vendo o que saiu do cardápio").hasSize(1);
    }

    @Test
    @DisplayName("a listagem traz os blocos de todos os serviços sem uma consulta por serviço")
    void listagem_traz_blocos() {
        UUID tenant = salao();
        TenantContext.executar(tenant, () -> {
            cadastrar.executar("Escova", null, List.of(ativo(45)));
            cadastrar.executar("Coloração", null, List.of(ativo(30), pausa(40), ativo(30)));
            cadastrar.executar("Manicure", null, List.of(ativo(50)));
        });

        var cardapio = TenantContext.obter(tenant, () -> catalogo.ativos());

        assertThat(cardapio).hasSize(3)
                .allSatisfy(s -> assertThat(s.blocos().blocos()).isNotEmpty());
        assertThat(cardapio).extracting(ServicoResumo::duracaoTotal)
                .containsExactlyInAnyOrder(45, 100, 50);
    }

    @Test
    @DisplayName("o cardápio de um salão não aparece no outro")
    void isolamento_entre_tenants() {
        UUID salaoA = salao();
        UUID salaoB = salao();
        var daA = TenantContext.obter(salaoA,
                () -> cadastrar.executar("Escova", null, List.of(ativo(45))));

        assertThat(TenantContext.obter(salaoB, () -> catalogo.ativos())).isEmpty();
        assertThat(TenantContext.obter(salaoB, () -> catalogo.porId(daA.id()))).isEmpty();
    }

    @Test
    @DisplayName("a recepção lê o cardápio mas não o altera")
    @WithMockUser(authorities = Permissao.AGENDA_LER_TODAS)
    void recepcao_le_mas_nao_altera() {
        // Mudar a duração de um serviço muda a agenda de todo mundo: não é operação de balcão.
        UUID tenant = salao();

        assertThat(TenantContext.obter(tenant, () -> listar.executar(false))).isEmpty();
        assertThatThrownBy(() -> TenantContext.executar(tenant,
                () -> cadastrar.executar("Escova", null, List.of(ativo(45)))))
                .isInstanceOf(AccessDeniedException.class);
    }
}
