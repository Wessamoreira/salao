package br.com.salao.cliente.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** RT-CNV-005 — a busca de clientes para o agente: o mínimo para escolher, e a tranca da tela. */
@WithMockUser(authorities = { Permissao.CLIENTE_LER, Permissao.CLIENTE_ESCREVER })
class FerramentaBuscarClientesIT extends AbstractPostgresIT {

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private CadastrarClienteUseCase cadastrar;
    @Autowired private FerramentaBuscarClientes ferramenta;

    private final JsonMapper json = JsonMapper.builder().build();

    private UUID salao() {
        return provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona",
                "dona-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
    }

    private JsonNode buscar(UUID tenant, String termo) {
        return TenantContext.obter(tenant,
                () -> ferramenta.executar(json.createObjectNode().put("termo", termo)));
    }

    @Test
    @DisplayName("devolve id, nome, apelido e só os 4 últimos dígitos — nunca o telefone inteiro nem o e-mail")
    void minimo_para_escolher() {
        UUID tenant = salao();
        UUID id = TenantContext.obter(tenant, () -> cadastrar.executar(new DadosDoCliente(
                "Marilda Souza", "Mari", "(11) 98765-4321", "marilda@salao.test", null)));

        JsonNode saida = buscar(tenant, "Marilda");

        assertThat(saida.path("interpretacao").asString()).isEqualTo("NOME");
        JsonNode item = saida.path("clientes").get(0);
        assertThat(item.path("id").asString()).isEqualTo(id.toString());
        assertThat(item.path("nome").asString()).isEqualTo("Marilda Souza");
        assertThat(item.path("apelido").asString()).isEqualTo("Mari");
        assertThat(item.path("telefone_final").asString()).isEqualTo("4321");
        assertThat(saida.toString()).doesNotContain("987654321").doesNotContain("marilda@");
    }

    @Test
    @DisplayName("os 4 últimos dígitos também encontram")
    void pelos_ultimos_digitos() {
        UUID tenant = salao();
        TenantContext.obter(tenant, () -> cadastrar.executar(
                DadosDoCliente.rapido("Marilda Souza", "(11) 98765-4321")));

        JsonNode saida = buscar(tenant, "4321");

        assertThat(saida.path("interpretacao").asString()).isEqualTo("TELEFONE");
        assertThat(saida.path("clientes").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("termo curto devolve o aviso do domínio, sem lista")
    void termo_curto() {
        UUID tenant = salao();

        JsonNode saida = buscar(tenant, "M");

        assertThat(saida.path("interpretacao").asString()).isEqualTo("INSUFICIENTE");
        assertThat(saida.path("aviso").asString()).isNotBlank();
        assertThat(saida.path("clientes").size()).isZero();
    }

    @Test
    @DisplayName("no máximo dez: dez Marias pedem um termo melhor, não uma lista maior")
    void no_maximo_dez() {
        UUID tenant = salao();
        for (int i = 1; i <= 12; i++) {
            String nome = "Maria " + (char) ('A' + i);
            TenantContext.obter(tenant, () -> cadastrar.executar(
                    new DadosDoCliente(nome, null, null, null, null)));
        }

        assertThat(buscar(tenant, "Maria").path("clientes").size())
                .isEqualTo(FerramentaBuscarClientes.LIMITE);
    }

    @Test
    @DisplayName("sem cliente:read, o caso de uso nega")
    void sem_permissao() {
        UUID tenant = salao();
        var anterior = SecurityContextHolder.getContext();
        var contexto = SecurityContextHolder.createEmptyContext();
        contexto.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("painel", null,
                List.of(new SimpleGrantedAuthority(Permissao.AGENDA_LER_TODAS))));
        SecurityContextHolder.setContext(contexto);
        try {
            assertThat(catchThrowable(() -> buscar(tenant, "Marilda")))
                    .isInstanceOf(AccessDeniedException.class);
        } finally {
            SecurityContextHolder.setContext(anterior);
        }
    }
}
