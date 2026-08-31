package br.com.salao.cliente.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.salao.cliente.api.ClienteResumo;
import br.com.salao.cliente.api.ResultadoDaBusca.Interpretacao;
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
import org.springframework.security.test.context.support.WithMockUser;

/** RT-CLI-002 — busca de cliente. */
@WithMockUser(authorities = { Permissao.CLIENTE_ESCREVER, Permissao.CLIENTE_LER })
class BuscaDeClienteIT extends AbstractPostgresIT {

    @Autowired
    private ProvisionarEstabelecimentoUseCase provisionar;

    @Autowired
    private CadastrarClienteUseCase cadastrar;

    @Autowired
    private BuscarClientesUseCase buscar;

    private UUID salao() {
        return provisionar.executar(new ProvisionarEstabelecimentoCommand(
                "Salão " + UUID.randomUUID(), null, null, null, null, null, null, "11",
                "Ana", "ana-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
    }

    /** Um salão pequeno e realista: nomes parecidos, apelidos, e um telefone compartilhado. */
    private UUID salaoPovoado() {
        UUID tenant = salao();
        TenantContext.executar(tenant, () -> {
            cadastrar.executar(new DadosDoCliente("Maria Silva", null, "11987654321", null, null));
            cadastrar.executar(new DadosDoCliente("Maria Silvana Souza", null, "1133334444",
                    null, null));
            cadastrar.executar(new DadosDoCliente("Juliana Gonçalves", "Ju", "11955554321",
                    null, null));
            cadastrar.executar(new DadosDoCliente("José Antônio", "Zé", "11966667890",
                    null, null));
            // Filha da Maria Silva: mesmo telefone, cadastro próprio.
            cadastrar.executar(new DadosDoCliente("Júlia Silva", null, "11987654321", null, null));
        });
        return tenant;
    }

    private List<String> nomes(UUID tenant, String termo) {
        return TenantContext.obter(tenant, () -> buscar.executar(termo, null))
                .itens().stream().map(ClienteResumo::nome).toList();
    }

    @Test
    @DisplayName("os 4 últimos dígitos acham quem está ligando — inclusive as duas do mesmo número")
    void busca_pelos_quatro_ultimos_digitos() {
        // R-UX-03: o caso mais comum do balcão. Buscar por nome falha (oito Marias, apelidos,
        // grafia); os quatro últimos dígitos resolvem na primeira tentativa.
        UUID tenant = salaoPovoado();

        assertThat(nomes(tenant, "4321"))
                .containsExactlyInAnyOrder("Maria Silva", "Júlia Silva", "Juliana Gonçalves");
    }

    @Test
    @DisplayName("o número inteiro, com ou sem máscara, acha o mesmo")
    void busca_pelo_numero_inteiro() {
        UUID tenant = salaoPovoado();

        assertThat(nomes(tenant, "(11) 98765-4321"))
                .containsExactlyInAnyOrder("Maria Silva", "Júlia Silva");
        assertThat(nomes(tenant, "11987654321"))
                .containsExactlyInAnyOrder("Maria Silva", "Júlia Silva");
    }

    @Test
    @DisplayName("acento não atrapalha, nos dois sentidos")
    void busca_ignora_acento() {
        // Quem digita com o telefone na orelha não vai parar para achar o circunflexo. E quem
        // cadastrou pode ter escrito sem acento — os dois lados precisam se encontrar.
        UUID tenant = salaoPovoado();

        assertThat(nomes(tenant, "goncalves")).containsExactly("Juliana Gonçalves");
        assertThat(nomes(tenant, "Gonçalves")).containsExactly("Juliana Gonçalves");
        assertThat(nomes(tenant, "jose")).containsExactly("José Antônio");
    }

    @Test
    @DisplayName("acha pelo apelido, que é como a recepção conhece a pessoa")
    void busca_pelo_apelido() {
        UUID tenant = salaoPovoado();

        assertThat(nomes(tenant, "Zé")).containsExactly("José Antônio");
    }

    @Test
    @DisplayName("acha pelo sobrenome, não só pelo começo do nome")
    void busca_no_meio_do_nome() {
        UUID tenant = salaoPovoado();

        assertThat(nomes(tenant, "silva"))
                .containsExactlyInAnyOrder("Maria Silva", "Júlia Silva", "Maria Silvana Souza");
    }

    @Test
    @DisplayName("quem começa com o termo aparece antes")
    void ordena_por_relevancia() {
        // Quem digita "maria s" espera "Maria Silva" antes de "Maria Silvana Souza": a diferença
        // entre uma busca que serve e uma que obriga a ler a lista inteira.
        UUID tenant = salaoPovoado();

        // "Maria Silva" e "Maria Silvana Souza" empatam nos dois primeiros critérios; o que
        // decide é a semelhança, e o termo cobre quase todo o nome da primeira.
        assertThat(nomes(tenant, "maria s")).first().isEqualTo("Maria Silva");

        // "ju" casa Juliana e Júlia. Não afirmo ordem entre elas: seria depender de um empate
        // que eu não verifiquei ser estável, e teste frágil só ensina a ignorar teste.
        assertThat(nomes(tenant, "ju")).contains("Juliana Gonçalves", "Júlia Silva");
    }

    @Test
    @DisplayName("a resposta diz o que o servidor entendeu do termo")
    void resposta_diz_o_que_entendeu() {
        // É isso que permite à tela escrever "buscando pelos 4 últimos dígitos" sem reimplementar
        // RN-CLI-009 em TypeScript — onde a cópia divergiria em silêncio.
        UUID tenant = salaoPovoado();

        assertThat(TenantContext.obter(tenant, () -> buscar.executar("4321", null)))
                .satisfies(r -> {
                    assertThat(r.interpretacao()).isEqualTo(Interpretacao.TELEFONE);
                    assertThat(r.aviso()).isNull();
                });
        assertThat(TenantContext.obter(tenant, () -> buscar.executar("maria", null))
                .interpretacao()).isEqualTo(Interpretacao.NOME);

        // O que falta também vem do domínio: a tela não reescreve "informe ao menos 2 letras".
        assertThat(TenantContext.obter(tenant, () -> buscar.executar("j", null)))
                .satisfies(r -> {
                    assertThat(r.interpretacao()).isEqualTo(Interpretacao.INSUFICIENTE);
                    assertThat(r.aviso()).contains("2 letras");
                });
    }

    @Test
    @DisplayName("termo curto devolve lista vazia, não erro")
    void termo_curto_nao_e_erro() {
        UUID tenant = salaoPovoado();

        assertThat(nomes(tenant, "j")).isEmpty();
        assertThat(nomes(tenant, "43")).isEmpty();
        assertThat(nomes(tenant, "")).isEmpty();
        assertThat(nomes(tenant, null)).isEmpty();
    }

    @Test
    @DisplayName("curinga do LIKE não vaza a agenda inteira")
    void curinga_nao_lista_todo_mundo() {
        // Sem neutralizar % e _, quem digitasse "%" veria o salão todo. Não é injeção — é
        // resultado errado, que é o tipo de falha que ninguém reporta e todo mundo usa.
        UUID tenant = salaoPovoado();

        assertThat(nomes(tenant, "%%")).isEmpty();
        assertThat(nomes(tenant, "___")).isEmpty();
    }

    @Test
    @DisplayName("o limite protege de termo genérico demais")
    void respeita_o_limite() {
        UUID tenant = salao();
        TenantContext.executar(tenant, () -> {
            for (int i = 0; i < 30; i++) {
                cadastrar.executar(DadosDoCliente.rapido("Maria " + i, null));
            }
        });

        assertThat(TenantContext.obter(tenant, () -> buscar.executar("maria", null)).itens())
                .hasSize(BuscarClientesUseCase.LIMITE_PADRAO);
        assertThat(TenantContext.obter(tenant, () -> buscar.executar("maria", 5)).itens())
                .hasSize(5);
        assertThat(TenantContext.obter(tenant, () -> buscar.executar("maria", 999)).itens())
                .hasSizeLessThanOrEqualTo(BuscarClientesUseCase.LIMITE_MAXIMO);
    }

    @Test
    @DisplayName("a busca não atravessa salões")
    void isolamento_entre_tenants() {
        UUID salaoA = salaoPovoado();
        UUID salaoB = salao();

        assertThat(nomes(salaoA, "4321")).isNotEmpty();
        assertThat(nomes(salaoB, "4321")).isEmpty();
        assertThat(nomes(salaoB, "maria")).isEmpty();
    }

    @Test
    @DisplayName("sem cliente:read, buscar é recusado no caso de uso")
    @WithMockUser(authorities = Permissao.AGENDA_LER_TODAS)
    void sem_permissao_nao_busca() {
        UUID tenant = salao();

        assertThatThrownBy(() -> TenantContext.executar(tenant, () -> buscar.executar("ana", null)))
                .isInstanceOf(AccessDeniedException.class);
    }
}
