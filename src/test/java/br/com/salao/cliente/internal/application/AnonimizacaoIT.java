package br.com.salao.cliente.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.cliente.api.CanalDeConsentimento;
import br.com.salao.cliente.api.ClienteApi;
import br.com.salao.cliente.api.Finalidade;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-CLI-005 — anonimização a pedido do titular. */
@WithMockUser(authorities = { Permissao.CLIENTE_ESCREVER, Permissao.CLIENTE_LER,
        Permissao.CLIENTE_ANONIMIZAR })
class AnonimizacaoIT extends AbstractPostgresIT {

    @Autowired
    private ProvisionarEstabelecimentoUseCase provisionar;

    @Autowired
    private CadastrarClienteUseCase cadastrar;

    @Autowired
    private BuscarClientesUseCase buscar;

    @Autowired
    private MesclarClientesUseCase mesclar;

    @Autowired
    private RegistrarConsentimentoUseCase consentir;

    @Autowired
    private AnonimizarClienteUseCase anonimizar;

    @Autowired
    private ClienteApi clienteApi;

    private UUID salao() {
        return provisionar.executar(new ProvisionarEstabelecimentoCommand(
                "Salão " + UUID.randomUUID(), null, null, null, null, null, null, "11",
                "Ana", "ana-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
    }

    private UUID clienteCompleto(UUID tenant) {
        return TenantContext.obter(tenant, () -> cadastrar.executar(new DadosDoCliente(
                "Maria Silva", "Mari", "11987654321", "maria@teste.com", "prefere manhã")));
    }

    /** Lido pelo owner, ignorando os filtros da aplicação: é o que sobra de verdade no disco. */
    private String[] linhaCrua(UUID id) throws SQLException {
        try (var ps = comoOwner().prepareStatement(
                "select nome, apelido, telefone, email, observacao, busca from cliente "
                        + "where id = ?")) {
            ps.setObject(1, id);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return new String[] { rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6) };
            }
        }
    }

    @Test
    @DisplayName("o dado pessoal some do disco, e a linha continua existindo")
    void apaga_o_dado_e_mantem_a_linha() throws SQLException {
        // O histórico financeiro aponta para o cliente e a legislação fiscal exige guardá-lo.
        // Apagar a linha quebraria o extrato de comissão de quem trabalhou — o direito do titular
        // é sobre o dado pessoal, não sobre o registro contábil do salão.
        UUID tenant = salao();
        UUID cliente = clienteCompleto(tenant);

        TenantContext.executar(tenant, () -> anonimizar.executar(cliente));

        assertThat(linhaCrua(cliente))
                .as("nada de identificável sobra, nem na coluna gerada de busca")
                .containsExactly(AnonimizarClienteUseCase.ROTULO, null, null, null, null,
                        "cliente anonimizado ");
    }

    @Test
    @DisplayName("some da consulta e da busca")
    void some_das_leituras() {
        UUID tenant = salao();
        UUID cliente = clienteCompleto(tenant);

        TenantContext.executar(tenant, () -> anonimizar.executar(cliente));

        assertThat(TenantContext.obter(tenant, () -> clienteApi.existe(cliente))).isFalse();
        assertThat(TenantContext.obter(tenant, () -> clienteApi.porId(cliente))).isEmpty();
        assertThat(TenantContext.obter(tenant, () -> buscar.executar("maria", null)).itens())
                .isEmpty();
        assertThat(TenantContext.obter(tenant, () -> buscar.executar("4321", null)).itens())
                .isEmpty();
    }

    @Test
    @DisplayName("alcança as fichas absorvidas por esta")
    void alcanca_as_fichas_absorvidas() throws SQLException {
        // A ficha absorvida continua guardando o nome e o telefone com que foi cadastrada, e é a
        // mesma pessoa. Anonimizar só a sobrevivente deixaria o dado intacto na ficha ao lado —
        // exatamente onde ninguém pensaria em olhar.
        UUID tenant = salao();
        var ids = TenantContext.obter(tenant, () -> new UUID[] {
                cadastrar.executar(DadosDoCliente.rapido("Maria Silva", "11987654321")),
                cadastrar.executar(DadosDoCliente.rapido("Maria da Silva", "11999998888")) });
        TenantContext.executar(tenant, () -> mesclar.executar(ids[0], ids[1]));

        TenantContext.executar(tenant, () -> anonimizar.executar(ids[0]));

        assertThat(linhaCrua(ids[1])[0]).isEqualTo(AnonimizarClienteUseCase.ROTULO);
        assertThat(linhaCrua(ids[1])[2]).as("o telefone da ficha absorvida também some").isNull();
    }

    @Test
    @DisplayName("depois de anonimizada, não recebe mensagem nenhuma")
    void nao_recebe_mais_mensagem() {
        // Nem o que seria permitido por padrão: mandar lembrete para quem pediu para ser
        // esquecida seria o pedido sendo atendido no banco e ignorado na prática.
        UUID tenant = salao();
        UUID cliente = clienteCompleto(tenant);
        TenantContext.executar(tenant, () -> consentir.executar(cliente, Finalidade.MARKETING,
                true, CanalDeConsentimento.BALCAO));

        TenantContext.executar(tenant, () -> anonimizar.executar(cliente));

        assertThat(TenantContext.obter(tenant,
                () -> clienteApi.podeReceber(cliente, Finalidade.LEMBRETE_DE_AGENDAMENTO)))
                .isFalse();
        assertThat(TenantContext.obter(tenant,
                () -> clienteApi.podeReceber(cliente, Finalidade.MARKETING))).isFalse();
    }

    @Test
    @DisplayName("a trilha de auditoria não guarda o nome de nenhum cliente")
    void trilha_nao_guarda_nome() throws SQLException {
        // Encontrado escrevendo esta rotina: o cadastro mandava o nome para a auditoria, uma
        // tabela append-only que a aplicação não pode alterar. Anonimizar a tabela `cliente`
        // deixaria o nome recuperável ali pelo entidade_id — o pedido do titular atendido pela
        // metade, na metade que ninguém conferiria.
        UUID tenant = salao();
        clienteCompleto(tenant);

        try (var st = comoOwner().createStatement();
             var rs = st.executeQuery(
                     "select count(*) from auditoria where entidade = 'cliente' "
                             + "and depois::text ilike '%Maria%'")) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    @Test
    @DisplayName("anonimizada não participa de mesclagem")
    void anonimizada_nao_mescla() {
        // Mesclar copia campos entre fichas: uma anonimizada receberia de volta dados que o
        // titular pediu para apagar, desfazendo a anonimização por um caminho lateral.
        UUID tenant = salao();
        var ids = TenantContext.obter(tenant, () -> new UUID[] {
                cadastrar.executar(DadosDoCliente.rapido("Maria Silva", "11987654321")),
                cadastrar.executar(DadosDoCliente.rapido("Maria da Silva", "11999998888")) });
        TenantContext.executar(tenant, () -> anonimizar.executar(ids[1]));

        var erro = catchThrowable(() ->
                TenantContext.executar(tenant, () -> mesclar.executar(ids[0], ids[1])));

        assertThat(((ErroDeDominio) erro).codigo().codigo())
                .isEqualTo("ER-CLI-MESCLAGEM_INVALIDA");
    }

    @Test
    @DisplayName("anonimizar de novo não diz 'pronto' sobre ficha que já sumiu")
    void repetir_nao_e_silencioso() {
        UUID tenant = salao();
        UUID cliente = clienteCompleto(tenant);
        TenantContext.executar(tenant, () -> anonimizar.executar(cliente));

        var erro = catchThrowable(() ->
                TenantContext.executar(tenant, () -> anonimizar.executar(cliente)));

        assertThat(((ErroDeDominio) erro).codigo().codigo())
                .isEqualTo("ER-CLI-CLIENTE_NAO_ENCONTRADO");
    }

    @Test
    @DisplayName("não atravessa salões")
    void isolamento_entre_tenants() throws SQLException {
        UUID salaoA = salao();
        UUID salaoB = salao();
        UUID daA = clienteCompleto(salaoA);

        var erro = catchThrowable(() ->
                TenantContext.executar(salaoB, () -> anonimizar.executar(daA)));

        assertThat(((ErroDeDominio) erro).codigo().codigo())
                .isEqualTo("ER-CLI-CLIENTE_NAO_ENCONTRADO");
        assertThat(linhaCrua(daA)[0]).as("a ficha do outro salão continua intacta")
                .isEqualTo("Maria Silva");
    }

    @Test
    @DisplayName("cliente:write não basta — anonimizar tem permissão própria")
    @WithMockUser(authorities = { Permissao.CLIENTE_ESCREVER, Permissao.CLIENTE_LER })
    void exige_permissao_propria() {
        // A recepção cadastra e corrige o dia inteiro; apagar o dado de alguém para sempre é
        // outro tipo de ato, e não deve estar a um clique de quem está atendendo o balcão.
        UUID tenant = salao();
        UUID cliente = clienteCompleto(tenant);

        assertThatThrownBy(() ->
                TenantContext.executar(tenant, () -> anonimizar.executar(cliente)))
                .isInstanceOf(AccessDeniedException.class);
    }
}
