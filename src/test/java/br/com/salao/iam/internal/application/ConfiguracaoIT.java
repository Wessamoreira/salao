package br.com.salao.iam.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.cliente.api.ClienteApi;
import br.com.salao.cliente.internal.application.CadastrarClienteUseCase;
import br.com.salao.cliente.internal.application.DadosDoCliente;
import br.com.salao.iam.api.BaseDeComissao;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.PeriodicidadeDeFechamento;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-CFG-001 — configuração do salão. */
@WithMockUser(authorities = { Permissao.CONFIG_GERENCIAR, Permissao.CLIENTE_ESCREVER,
        Permissao.CLIENTE_LER })
class ConfiguracaoIT extends AbstractPostgresIT {

    @Autowired
    private ProvisionarEstabelecimentoUseCase provisionar;

    @Autowired
    private AtualizarConfiguracaoUseCase atualizar;

    @Autowired
    private EstabelecimentoApi estabelecimentos;

    @Autowired
    private CadastrarClienteUseCase cadastrarCliente;

    @Autowired
    private ClienteApi clientes;

    private UUID salaoSemDdd() {
        return provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Ana",
                "ana-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
    }

    private ConfiguracaoCommand comDdd(String ddd) {
        return new ConfiguracaoCommand("Studio Marilda", null, "America/Sao_Paulo", "BRL",
                BaseDeComissao.BRUTO, false, PeriodicidadeDeFechamento.MENSAL, ddd);
    }

    private String dddDe(UUID tenant) {
        return TenantContext.obter(tenant,
                () -> estabelecimentos.configuracao(tenant).orElseThrow().dddPadrao());
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    @Test
    @DisplayName("define o DDD padrão e o cadastro de cliente volta a aceitar número sem DDD")
    void define_o_ddd_e_destrava_o_cadastro() {
        // É o buraco que esta rotina existe para fechar: o ddd_padrao entrou em RT-CLI-001 e só
        // podia ser definido no provisionamento. Sem ele, o salão recusa todo telefone digitado
        // como quem atende anota — e não havia como corrigir a não ser no banco.
        UUID tenant = salaoSemDdd();

        var erroAntes = catchThrowable(() -> TenantContext.executar(tenant,
                () -> cadastrarCliente.executar(DadosDoCliente.rapido("Maria", "98765-4321"))));
        assertThat(codigoDe(erroAntes)).isEqualTo("ER-CLI-TELEFONE_INVALIDO");

        TenantContext.executar(tenant, () -> atualizar.executar(comDdd("11")));

        UUID cliente = TenantContext.obter(tenant,
                () -> cadastrarCliente.executar(DadosDoCliente.rapido("Maria", "98765-4321")));
        assertThat(TenantContext.obter(tenant, () -> clientes.porId(cliente).orElseThrow()
                .telefone())).isEqualTo("5511987654321");
    }

    @Test
    @DisplayName("a leitura seguinte já vê o valor novo — o cache é invalidado")
    void cache_e_invalidado() {
        // Sem o NOTIFY, o salão trocaria o DDD e continuaria recusando telefone por até cinco
        // minutos: o defeito que faz alguém concluir que "não salvou" e tentar de novo.
        UUID tenant = salaoSemDdd();
        // Aquece o cache com o valor antigo.
        assertThat(dddDe(tenant)).isNull();

        TenantContext.executar(tenant, () -> atualizar.executar(comDdd("21")));

        // "21".equals(...) e não o contrário: o valor lido é nulo até a invalidação
        // chegar, e a ordem ingênua estouraria NPE em vez de esperar.
        aguardar(() -> "21".equals(dddDe(tenant)), "cache não invalidado", 5);
    }

    @Test
    @DisplayName("o DDD é normalizado: (11) e 011 viram 11")
    void ddd_e_normalizado() {
        UUID tenant = salaoSemDdd();

        TenantContext.executar(tenant, () -> atualizar.executar(comDdd("(11)")));

        // "11".equals(...) e não o contrário: o valor lido é nulo até a invalidação
        // chegar, e a ordem ingênua estouraria NPE em vez de esperar.
        aguardar(() -> "11".equals(dddDe(tenant)), "cache não invalidado", 5);
    }

    @Test
    @DisplayName("recusa DDD com zero a mais, como no provisionamento")
    void recusa_ddd_invalido() {
        // Mesma validação, mesmo objeto: uma segunda validação para edição aceitaria, um dia, o
        // que a criação recusa.
        UUID tenant = salaoSemDdd();

        var erro = catchThrowable(() ->
                TenantContext.executar(tenant, () -> atualizar.executar(comDdd("0119"))));

        assertThat(codigoDe(erro)).isEqualTo("ER-IAM-DADOS_INVALIDOS");
    }

    @Test
    @DisplayName("recusa offset fixo no fuso, como no provisionamento")
    void recusa_offset_fixo() {
        UUID tenant = salaoSemDdd();

        var erro = catchThrowable(() -> TenantContext.executar(tenant,
                () -> atualizar.executar(new ConfiguracaoCommand("Studio", null, "-03:00", "BRL",
                        BaseDeComissao.BRUTO, false, PeriodicidadeDeFechamento.MENSAL, "11"))));

        assertThat(codigoDe(erro)).isEqualTo("ER-IAM-DADOS_INVALIDOS");
    }

    @Test
    @DisplayName("altera a regra de comissão e o fuso")
    void altera_comissao_e_fuso() {
        UUID tenant = salaoSemDdd();

        var salva = TenantContext.obter(tenant, () -> atualizar.executar(
                new ConfiguracaoCommand("Studio Marilda", "12345678000199", "America/Manaus",
                        "BRL", BaseDeComissao.LIQUIDO, true,
                        PeriodicidadeDeFechamento.QUINZENAL, "92")));

        assertThat(salva.baseComissao()).isEqualTo(BaseDeComissao.LIQUIDO);
        assertThat(salva.descontoAfetaComissao()).isTrue();
        assertThat(salva.periodicidadeDeFechamento())
                .isEqualTo(PeriodicidadeDeFechamento.QUINZENAL);
        assertThat(salva.fuso()).isEqualTo(ZoneId.of("America/Manaus"));
    }

    @Test
    @DisplayName("a trilha guarda a regra de comissão antes e depois")
    void trilha_guarda_a_regra_de_comissao() throws Exception {
        // Enquanto o financeiro não gravar a regra vigente em cada fechamento, esta trilha é a
        // única resposta para "qual base valia em março?" — pergunta que aparece na primeira vez
        // que um profissional contesta o extrato.
        UUID tenant = salaoSemDdd();

        TenantContext.executar(tenant, () -> atualizar.executar(
                new ConfiguracaoCommand("Studio", null, "America/Sao_Paulo", "BRL",
                        BaseDeComissao.LIQUIDO, true, PeriodicidadeDeFechamento.MENSAL, "11")));

        try (var st = comoOwner().createStatement();
             var rs = st.executeQuery("""
                     select antes::text, depois::text from auditoria
                      where acao = 'CONFIGURACAO_ALTERADA'
                     """)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).contains("BRUTO");
            assertThat(rs.getString(2)).contains("LIQUIDO");
        }
    }

    @Test
    @DisplayName("a configuração de um salão não vaza nem altera a de outro")
    void isolamento_entre_tenants() {
        UUID salaoA = salaoSemDdd();
        UUID salaoB = salaoSemDdd();

        TenantContext.executar(salaoA, () -> atualizar.executar(comDdd("11")));

        aguardar(() -> dddDe(salaoB) == null, "a configuração do outro salão foi afetada", 5);
    }

    @Test
    @DisplayName("sem config:manage, alterar é recusado no caso de uso")
    @WithMockUser(authorities = Permissao.RELATORIO_LER)
    void sem_permissao_nao_altera() {
        UUID tenant = salaoSemDdd();

        assertThatThrownBy(() -> TenantContext.executar(tenant,
                () -> atualizar.executar(comDdd("11"))))
                .isInstanceOf(AccessDeniedException.class);
    }
}
