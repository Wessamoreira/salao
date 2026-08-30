package br.com.salao.cliente.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.cliente.api.ClienteApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/** RT-CLI-001 — cadastro de cliente. */
class CadastroDeClienteIT extends AbstractPostgresIT {

    @Autowired
    private ProvisionarEstabelecimentoUseCase provisionar;

    @Autowired
    private CadastrarClienteUseCase cadastrar;

    @Autowired
    private ConsultarClienteUseCase consultar;

    @Autowired
    private ClienteApi clienteApi;

    private UUID salaoComDdd(String ddd) {
        return provisionar.executar(new ProvisionarEstabelecimentoCommand(
                "Salão " + UUID.randomUUID(), null, null, null, null, null, null, ddd,
                "Ana", "ana-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    @Test
    @WithMockUser(authorities = { Permissao.CLIENTE_ESCREVER, Permissao.CLIENTE_LER })
    @DisplayName("cadastro rápido: nome e telefone bastam")
    void cadastro_rapido() {
        // R-UX-04. O cadastro nasce no meio de um agendamento, com a cliente esperando: exigir
        // e-mail para marcar um corte é a razão nº 1 de sistema de salão virar caderno.
        UUID tenant = salaoComDdd("11");

        var cliente = TenantContext.obter(tenant, () -> {
            UUID id = cadastrar.executar(DadosDoCliente.rapido("Maria Silva", "(11) 98765-4321"));
            return consultar.executar(id);
        });

        assertThat(cliente.nome()).isEqualTo("Maria Silva");
        assertThat(cliente.telefone()).isEqualTo("5511987654321");
        assertThat(cliente.email()).isNull();
    }

    @Test
    @WithMockUser(authorities = { Permissao.CLIENTE_ESCREVER, Permissao.CLIENTE_LER })
    @DisplayName("telefone sem DDD é completado com o do salão")
    void completa_o_ddd_do_salao() {
        UUID tenant = salaoComDdd("21");

        var cliente = TenantContext.obter(tenant, () -> consultar.executar(
                cadastrar.executar(DadosDoCliente.rapido("Joana", "98765-4321"))));

        assertThat(cliente.telefone()).isEqualTo("5521987654321");
    }

    @Test
    @WithMockUser(authorities = Permissao.CLIENTE_ESCREVER)
    @DisplayName("sem DDD configurado no salão, recusa em vez de chutar")
    void sem_ddd_configurado_recusa() {
        UUID tenant = salaoComDdd(null);

        var erro = catchThrowable(() -> TenantContext.executar(tenant, () ->
                cadastrar.executar(DadosDoCliente.rapido("Joana", "98765-4321"))));

        assertThat(codigoDe(erro)).isEqualTo("ER-CLI-TELEFONE_INVALIDO");
    }

    @Test
    @WithMockUser(authorities = { Permissao.CLIENTE_ESCREVER, Permissao.CLIENTE_LER })
    @DisplayName("cliente sem telefone é válido")
    void cliente_sem_telefone() {
        // Cliente que passa e pergunta o preço, criança que vem com a mãe, a senhora sem celular.
        // Exigir telefone faria a recepção digitar 00000-0000, que parece dado e não é.
        UUID tenant = salaoComDdd("11");

        var cliente = TenantContext.obter(tenant, () -> consultar.executar(
                cadastrar.executar(new DadosDoCliente("Dona Zilda", "Zilda", null, null, null))));

        assertThat(cliente.telefone()).isNull();
        assertThat(cliente.comoChamar()).isEqualTo("Zilda");
    }

    @Test
    @WithMockUser(authorities = { Permissao.CLIENTE_ESCREVER, Permissao.CLIENTE_LER })
    @DisplayName("mãe e filha podem dividir o mesmo telefone")
    void telefone_repetido_e_permitido() {
        // Decisão de domínio, não descuido: uma unique aqui recusaria cadastro legítimo e
        // ensinaria a recepção a inventar um número falso — pior que o duplicado.
        // Duplicado é DETECTADO no cadastro (RT-CLI-003), não proibido.
        UUID tenant = salaoComDdd("11");

        var ids = TenantContext.obter(tenant, () -> new UUID[] {
                cadastrar.executar(DadosDoCliente.rapido("Maria Silva", "11987654321")),
                cadastrar.executar(DadosDoCliente.rapido("Júlia Silva", "11987654321")) });

        assertThat(ids[0]).isNotEqualTo(ids[1]);
    }

    @Test
    @WithMockUser(authorities = Permissao.CLIENTE_ESCREVER)
    @DisplayName("nome vazio é recusado com o campo apontado")
    void nome_vazio_recusado() {
        UUID tenant = salaoComDdd("11");

        var erro = catchThrowable(() -> TenantContext.executar(tenant, () ->
                cadastrar.executar(DadosDoCliente.rapido("   ", "11987654321"))));

        assertThat(codigoDe(erro)).isEqualTo("ER-CLI-NOME_OBRIGATORIO");
        assertThat(((ErroDeDominio) erro).campos()).singleElement()
                .extracting("campo").isEqualTo("nome");
    }

    @Test
    @WithMockUser(authorities = { Permissao.CLIENTE_ESCREVER, Permissao.CLIENTE_LER })
    @DisplayName("espaço a mais no nome não cria uma segunda pessoa")
    void normaliza_espacos_do_nome() {
        UUID tenant = salaoComDdd("11");

        var cliente = TenantContext.obter(tenant, () -> consultar.executar(
                cadastrar.executar(DadosDoCliente.rapido("  Maria   Silva ", null))));

        assertThat(cliente.nome()).isEqualTo("Maria Silva");
    }

    @Test
    @WithMockUser(authorities = Permissao.AGENDA_LER_TODAS)
    @DisplayName("sem cliente:write, cadastrar é recusado — a autorização está no caso de uso")
    void sem_permissao_nao_cadastra() {
        // Colocar a autorização no controller deixaria o bot da Fase 4 de fora, porque ele
        // chama o caso de uso diretamente.
        UUID tenant = salaoComDdd("11");

        assertThatThrownBy(() -> TenantContext.executar(tenant, () ->
                cadastrar.executar(DadosDoCliente.rapido("Maria", null))))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(authorities = { Permissao.CLIENTE_ESCREVER, Permissao.CLIENTE_LER })
    @DisplayName("cliente de outro salão não existe para este — a RLS não devolve a linha")
    void isolamento_entre_tenants() {
        UUID salaoA = salaoComDdd("11");
        UUID salaoB = salaoComDdd("11");

        UUID daA = TenantContext.obter(salaoA, () ->
                cadastrar.executar(DadosDoCliente.rapido("Maria", "11987654321")));

        var erro = catchThrowable(() -> TenantContext.executar(salaoB,
                () -> consultar.executar(daA)));

        assertThat(codigoDe(erro)).isEqualTo("ER-CLI-CLIENTE_NAO_ENCONTRADO");
        assertThat(TenantContext.obter(salaoB, () -> clienteApi.existe(daA))).isFalse();
        assertThat(TenantContext.obter(salaoA, () -> clienteApi.existe(daA))).isTrue();
    }
}
