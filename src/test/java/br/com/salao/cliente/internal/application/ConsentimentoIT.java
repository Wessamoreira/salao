package br.com.salao.cliente.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.salao.cliente.api.CanalDeConsentimento;
import br.com.salao.cliente.api.ClienteApi;
import br.com.salao.cliente.api.EstadoDoConsentimento;
import br.com.salao.cliente.api.EstadoDoConsentimento.Decisao;
import br.com.salao.cliente.api.Finalidade;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-CLI-004 — consentimento por finalidade. */
@WithMockUser(authorities = { Permissao.CLIENTE_ESCREVER, Permissao.CLIENTE_LER })
class ConsentimentoIT extends AbstractPostgresIT {

    @Autowired
    private ProvisionarEstabelecimentoUseCase provisionar;

    @Autowired
    private CadastrarClienteUseCase cadastrar;

    @Autowired
    private MesclarClientesUseCase mesclar;

    @Autowired
    private RegistrarConsentimentoUseCase registrar;

    @Autowired
    private ConsultarConsentimentosUseCase consultar;

    @Autowired
    private ClienteApi clienteApi;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TransactionTemplate tx;

    private UUID salao() {
        return provisionar.executar(new ProvisionarEstabelecimentoCommand(
                "Salão " + UUID.randomUUID(), null, null, null, null, null, null, "11",
                "Ana", "ana-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
    }

    private EstadoDoConsentimento estado(UUID tenant, UUID cliente, Finalidade finalidade) {
        return TenantContext.obter(tenant, () -> consultar.executar(cliente)).stream()
                .filter(e -> e.finalidade() == finalidade).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("sem registro: o lembrete do horário pode sair, a promoção não")
    void padrao_depende_da_base_legal() {
        // É a distinção central da rotina. Exigir consentimento para avisar sobre um horário que a
        // própria cliente marcou sugeriria que ela pode recusar e ainda ser atendida igual — e
        // criaria uma revogação que o salão não teria como honrar.
        UUID tenant = salao();
        UUID cliente = TenantContext.obter(tenant,
                () -> cadastrar.executar(DadosDoCliente.rapido("Maria Silva", "11987654321")));

        assertThat(TenantContext.obter(tenant,
                () -> clienteApi.podeReceber(cliente, Finalidade.LEMBRETE_DE_AGENDAMENTO)))
                .isTrue();
        assertThat(TenantContext.obter(tenant,
                () -> clienteApi.podeReceber(cliente, Finalidade.MARKETING)))
                .isFalse();
    }

    @Test
    @DisplayName("toda finalidade aparece na consulta, mesmo a que ninguém perguntou")
    void lista_inclui_o_que_falta_perguntar() {
        // Uma lista que só mostra o registrado esconde justamente o que falta perguntar.
        UUID tenant = salao();
        UUID cliente = TenantContext.obter(tenant,
                () -> cadastrar.executar(DadosDoCliente.rapido("Maria Silva", null)));

        assertThat(TenantContext.obter(tenant, () -> consultar.executar(cliente)))
                .hasSize(Finalidade.values().length)
                .allMatch(e -> e.decisao() == Decisao.SEM_REGISTRO);
    }

    @Test
    @DisplayName("consentir libera; revogar pela mesma rota bloqueia de novo")
    void consentir_e_revogar() {
        UUID tenant = salao();
        UUID cliente = TenantContext.obter(tenant,
                () -> cadastrar.executar(DadosDoCliente.rapido("Maria Silva", null)));

        TenantContext.executar(tenant, () -> registrar.executar(cliente, Finalidade.MARKETING,
                true, CanalDeConsentimento.BALCAO, "perguntado no balcão"));
        assertThat(estado(tenant, cliente, Finalidade.MARKETING))
                .satisfies(e -> {
                    assertThat(e.decisao()).isEqualTo(Decisao.CONCEDIDO);
                    assertThat(e.podeReceber()).isTrue();
                    assertThat(e.canal()).isEqualTo(CanalDeConsentimento.BALCAO);
                    assertThat(e.desde()).isNotNull();
                });

        TenantContext.executar(tenant, () -> registrar.executar(cliente, Finalidade.MARKETING,
                false, CanalDeConsentimento.WHATSAPP, "pediu para parar"));
        assertThat(estado(tenant, cliente, Finalidade.MARKETING).podeReceber()).isFalse();
    }

    @Test
    @DisplayName("oposição ao lembrete é honrada, mesmo sendo execução de contrato")
    void oposicao_ao_lembrete() {
        // Art. 18 §2. Na prática, "não me manda mais mensagem" precisa ser honrado — e um sistema
        // que não sabe registrar isso empurra o salão para ignorar a cliente ou anotar num papel.
        UUID tenant = salao();
        UUID cliente = TenantContext.obter(tenant,
                () -> cadastrar.executar(DadosDoCliente.rapido("Maria Silva", null)));

        TenantContext.executar(tenant, () -> registrar.executar(cliente,
                Finalidade.LEMBRETE_DE_AGENDAMENTO, false, CanalDeConsentimento.TELEFONE, null));

        assertThat(TenantContext.obter(tenant,
                () -> clienteApi.podeReceber(cliente, Finalidade.LEMBRETE_DE_AGENDAMENTO)))
                .isFalse();
    }

    @Test
    @DisplayName("cada finalidade é independente")
    void finalidades_sao_independentes() {
        UUID tenant = salao();
        UUID cliente = TenantContext.obter(tenant,
                () -> cadastrar.executar(DadosDoCliente.rapido("Maria Silva", null)));

        TenantContext.executar(tenant, () -> registrar.executar(cliente, Finalidade.MARKETING,
                true, CanalDeConsentimento.BALCAO, null));

        assertThat(estado(tenant, cliente, Finalidade.MARKETING).podeReceber()).isTrue();
        assertThat(estado(tenant, cliente, Finalidade.PESQUISA_DE_SATISFACAO).decisao())
                .isEqualTo(Decisao.SEM_REGISTRO);
    }

    @Test
    @DisplayName("o histórico é append-only por PERMISSÃO: a aplicação não altera nem apaga")
    void historico_e_append_only() throws SQLException {
        // A pergunta a responder não é "o que vale hoje", é "o que valia no dia do envio". Uma
        // linha editável só sabe responder a primeira — a resposta inútil num questionamento.
        //
        // Imutabilidade por permissão, não por convenção: convenção depende de ninguém escrever
        // o UPDATE; a permissão revogada impede que ele funcione.
        UUID tenant = salao();
        UUID cliente = TenantContext.obter(tenant,
                () -> cadastrar.executar(DadosDoCliente.rapido("Maria Silva", null)));

        TenantContext.executar(tenant, () -> registrar.executar(cliente, Finalidade.MARKETING,
                true, CanalDeConsentimento.BALCAO, null));
        TenantContext.executar(tenant, () -> registrar.executar(cliente, Finalidade.MARKETING,
                false, CanalDeConsentimento.BALCAO, null));

        assertThatThrownBy(() -> comoAplicacao(tenant,
                "update cliente_consentimento set concedido = true"))
                // Na causa, e não na mensagem: o Spring embrulha o erro do driver. Assertar a
                // razão importa — falhar por outro motivo também passaria num
                // assertThatThrownBy genérico, e o teste não provaria nada.
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> comoAplicacao(tenant, "delete from cliente_consentimento"))
                .hasStackTraceContaining("permission denied");

        try (var st = comoOwner().createStatement();
             var rs = st.executeQuery(
                     "select count(*) from cliente_consentimento where finalidade = 'MARKETING'")) {
            rs.next();
            assertThat(rs.getInt(1)).as("as duas decisões continuam lá, intactas").isEqualTo(2);
        }
        assertThat(estado(tenant, cliente, Finalidade.MARKETING).podeReceber())
                .as("a revogação continua valendo").isFalse();
    }

    /** Executa pela conexão de {@code salao_app} — exatamente a role que não pode. */
    private void comoAplicacao(UUID tenant, String sql) {
        TenantContext.executar(tenant, () -> tx.executeWithoutResult(t ->
                JdbcClient.create(dataSource).sql(sql).update()));
    }

    @Test
    @DisplayName("a revogação sobrevive à mesclagem de fichas")
    void revogacao_sobrevive_a_mesclagem() {
        // Se a pessoa revogou numa ficha e a outra ainda dizia "sim", mesclar não pode ressuscitar
        // o consentimento. A decisão mais recente vence, venha de qual ficha vier.
        UUID tenant = salao();
        var ids = TenantContext.obter(tenant, () -> new UUID[] {
                cadastrar.executar(DadosDoCliente.rapido("Maria Silva", null)),
                cadastrar.executar(DadosDoCliente.rapido("Maria da Silva", null)) });

        TenantContext.executar(tenant, () -> registrar.executar(ids[0], Finalidade.MARKETING,
                true, CanalDeConsentimento.BALCAO, null));
        TenantContext.executar(tenant, () -> registrar.executar(ids[1], Finalidade.MARKETING,
                false, CanalDeConsentimento.WHATSAPP, "pediu para parar"));

        TenantContext.executar(tenant, () -> mesclar.executar(ids[0], ids[1]));

        assertThat(TenantContext.obter(tenant,
                () -> clienteApi.podeReceber(ids[0], Finalidade.MARKETING))).isFalse();
    }

    @Test
    @DisplayName("cliente de outro salão não recebe nada — falha fechada")
    void isolamento_entre_tenants() {
        UUID salaoA = salao();
        UUID salaoB = salao();
        UUID daA = TenantContext.obter(salaoA,
                () -> cadastrar.executar(DadosDoCliente.rapido("Maria Silva", null)));

        assertThat(TenantContext.obter(salaoB,
                () -> clienteApi.podeReceber(daA, Finalidade.LEMBRETE_DE_AGENDAMENTO)))
                .as("nem o que é permitido por padrão vaza para outro tenant")
                .isFalse();
    }

    @Test
    @DisplayName("sem cliente:write, registrar consentimento é recusado no caso de uso")
    @WithMockUser(authorities = Permissao.CLIENTE_LER)
    void sem_permissao_nao_registra() {
        UUID tenant = salao();

        assertThatThrownBy(() -> TenantContext.executar(tenant,
                () -> registrar.executar(UUID.randomUUID(), Finalidade.MARKETING, true,
                        CanalDeConsentimento.BALCAO, null)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("toda finalidade do enum é aceita pelo check do banco")
    void enum_e_banco_concordam() throws SQLException {
        // A divergência só apareceria como erro de escrita em produção, no momento em que alguém
        // tentasse registrar a decisão da cliente — e com a cliente na frente.
        UUID tenant = salao();
        UUID cliente = TenantContext.obter(tenant,
                () -> cadastrar.executar(DadosDoCliente.rapido("Maria Silva", null)));

        for (Finalidade finalidade : Finalidade.values()) {
            for (CanalDeConsentimento canal : CanalDeConsentimento.values()) {
                TenantContext.executar(tenant, () ->
                        registrar.executar(cliente, finalidade, true, canal, null));
            }
        }

        try (var st = comoOwner().createStatement();
             var rs = st.executeQuery("select count(*) from cliente_consentimento")) {
            rs.next();
            assertThat(rs.getInt(1))
                    .isEqualTo(Finalidade.values().length * CanalDeConsentimento.values().length);
        }
    }
}
