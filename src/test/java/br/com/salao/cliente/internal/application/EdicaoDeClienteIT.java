package br.com.salao.cliente.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.cliente.api.ClienteResumo;
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

/** RT-CLI-007 — corrigir a ficha. */
// Inclui cliente:anonymize porque um dos casos precisa criar o estado anonimizado. A separação
// dessa permissão tem teste próprio em AnonimizacaoIT.
@WithMockUser(authorities = { Permissao.CLIENTE_ESCREVER, Permissao.CLIENTE_LER,
        Permissao.CLIENTE_ANONIMIZAR })
class EdicaoDeClienteIT extends AbstractPostgresIT {

    @Autowired
    private ProvisionarEstabelecimentoUseCase provisionar;

    @Autowired
    private CadastrarClienteUseCase cadastrar;

    @Autowired
    private ConsultarClienteUseCase consultar;

    @Autowired
    private BuscarClientesUseCase buscar;

    @Autowired
    private AtualizarClienteUseCase atualizar;

    @Autowired
    private AnonimizarClienteUseCase anonimizar;

    private UUID salao() {
        return provisionar.executar(new ProvisionarEstabelecimentoCommand(
                "Salão " + UUID.randomUUID(), null, null, null, null, null, null, "11",
                "Ana", "ana-" + UUID.randomUUID() + "@salao.test", "senha-bem-comprida-1"));
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    @Test
    @DisplayName("corrige o telefone digitado errado — o erro mais comum do balcão")
    void corrige_o_telefone() {
        UUID tenant = salao();
        var ficha = TenantContext.obter(tenant, () -> consultar.executar(
                cadastrar.executar(DadosDoCliente.rapido("Maria Silva", "11987654321"))));

        var corrigida = TenantContext.obter(tenant, () -> atualizar.executar(ficha.id(),
                ficha.versao(),
                new DadosDoCliente("Maria Silva", null, "(11) 91234-5678", null, null)));

        assertThat(corrigida.telefone()).isEqualTo("5511912345678");
        assertThat(corrigida.versao()).isGreaterThan(ficha.versao());
    }

    @Test
    @DisplayName("o telefone passa pela mesma normalização do cadastro")
    void mesma_normalizacao_do_cadastro() {
        // Duas cópias da regra divergiriam, e a divergência apareceria como número que entra
        // certo pelo cadastro e errado pela correção — ou o contrário.
        UUID tenant = salao();
        var ficha = TenantContext.obter(tenant, () -> consultar.executar(
                cadastrar.executar(DadosDoCliente.rapido("Maria Silva", null))));

        var corrigida = TenantContext.obter(tenant, () -> atualizar.executar(ficha.id(),
                ficha.versao(), new DadosDoCliente("Maria Silva", null, "98765-4321", null, null)));

        assertThat(corrigida.telefone()).as("completou o DDD do salão").isEqualTo("5511987654321");
    }

    @Test
    @DisplayName("telefone impossível é recusado com o campo apontado")
    void telefone_invalido_recusado() {
        UUID tenant = salao();
        var ficha = TenantContext.obter(tenant, () -> consultar.executar(
                cadastrar.executar(DadosDoCliente.rapido("Maria Silva", null))));

        var erro = catchThrowable(() -> TenantContext.executar(tenant, () -> atualizar.executar(
                ficha.id(), ficha.versao(),
                new DadosDoCliente("Maria Silva", null, "1234", null, null))));

        assertThat(codigoDe(erro)).isEqualTo("ER-CLI-TELEFONE_INVALIDO");
    }

    @Test
    @DisplayName("a correção aparece na busca — a coluna gerada acompanha")
    void correcao_aparece_na_busca() {
        UUID tenant = salao();
        var ficha = TenantContext.obter(tenant, () -> consultar.executar(
                cadastrar.executar(DadosDoCliente.rapido("Maira Silva", null))));

        TenantContext.executar(tenant, () -> atualizar.executar(ficha.id(), ficha.versao(),
                new DadosDoCliente("Maria Silva", "Mari", null, null, null)));

        assertThat(TenantContext.obter(tenant, () -> buscar.executar("maria", null)).itens())
                .extracting(ClienteResumo::nome).containsExactly("Maria Silva");
        assertThat(TenantContext.obter(tenant, () -> buscar.executar("mari", null)).itens())
                .as("o apelido novo também entra na busca").hasSize(1);
    }

    @Test
    @DisplayName("campo apagado fica vazio — substitui, não mescla")
    void campo_apagado_fica_vazio() {
        // Distinguir "veio nulo" de "não veio" em JSON exige envelope e é fonte conhecida de bug.
        // A tela manda o formulário inteiro, então apagar é dizer explicitamente que está vazio.
        UUID tenant = salao();
        var ficha = TenantContext.obter(tenant, () -> consultar.executar(cadastrar.executar(
                new DadosDoCliente("Maria Silva", "Mari", "11987654321", "m@t.test", null))));

        var corrigida = TenantContext.obter(tenant, () -> atualizar.executar(ficha.id(),
                ficha.versao(), new DadosDoCliente("Maria Silva", null, "11987654321",
                        null, null)));

        assertThat(corrigida.apelido()).isNull();
        assertThat(corrigida.email()).isNull();
    }

    @Test
    @DisplayName("quem salva com versão vencida é avisado, e não apaga a correção alheia")
    void versao_vencida_e_recusada() {
        // Duas recepções abrem a mesma ficha; uma corrige o telefone, a outra corrige o nome e
        // salva depois. Sem a trava, a segunda gravação devolveria o telefone antigo em silêncio.
        UUID tenant = salao();
        var ficha = TenantContext.obter(tenant, () -> consultar.executar(
                cadastrar.executar(DadosDoCliente.rapido("Maria Silva", "11987654321"))));

        TenantContext.executar(tenant, () -> atualizar.executar(ficha.id(), ficha.versao(),
                new DadosDoCliente("Maria Silva", null, "11912345678", null, null)));

        var erro = catchThrowable(() -> TenantContext.executar(tenant, () -> atualizar.executar(
                ficha.id(), ficha.versao(),
                new DadosDoCliente("Maria Silva Santos", null, "11987654321", null, null))));

        assertThat(codigoDe(erro)).isEqualTo("ER-INF-VERSAO_DESATUALIZADA");
        assertThat(TenantContext.obter(tenant, () -> consultar.executar(ficha.id()).telefone()))
                .as("a correção da primeira pessoa continua lá").isEqualTo("5511912345678");
    }

    @Test
    @DisplayName("nome vazio é recusado, como no cadastro")
    void nome_vazio_recusado() {
        UUID tenant = salao();
        var ficha = TenantContext.obter(tenant, () -> consultar.executar(
                cadastrar.executar(DadosDoCliente.rapido("Maria Silva", null))));

        var erro = catchThrowable(() -> TenantContext.executar(tenant, () -> atualizar.executar(
                ficha.id(), ficha.versao(), new DadosDoCliente("  ", null, null, null, null))));

        assertThat(codigoDe(erro)).isEqualTo("ER-CLI-NOME_OBRIGATORIO");
    }

    @Test
    @DisplayName("ficha anonimizada não volta a ter dados por edição")
    void anonimizada_nao_e_editavel() {
        // Seria desfazer a anonimização por um caminho lateral — o pedido da titular atendido e
        // revertido pela tela de correção.
        UUID tenant = salao();
        var ficha = TenantContext.obter(tenant, () -> consultar.executar(
                cadastrar.executar(DadosDoCliente.rapido("Maria Silva", "11987654321"))));
        TenantContext.executar(tenant, () -> anonimizar.executar(ficha.id()));

        var erro = catchThrowable(() -> TenantContext.executar(tenant, () -> atualizar.executar(
                ficha.id(), ficha.versao(),
                new DadosDoCliente("Maria Silva", null, "11987654321", null, null))));

        assertThat(codigoDe(erro)).isEqualTo("ER-CLI-CLIENTE_NAO_ENCONTRADO");
    }

    @Test
    @DisplayName("a trilha registra quais campos mudaram, nunca os valores")
    void trilha_sem_dado_pessoal() throws SQLException {
        // A auditoria é append-only e a anonimização não a alcança (RN-CLI-007): guardar aqui o
        // telefone antigo criaria a segunda base de dado pessoal que ninguém sabe limpar.
        UUID tenant = salao();
        var ficha = TenantContext.obter(tenant, () -> consultar.executar(
                cadastrar.executar(DadosDoCliente.rapido("Maria Silva", "11987654321"))));

        TenantContext.executar(tenant, () -> atualizar.executar(ficha.id(), ficha.versao(),
                new DadosDoCliente("Maria Silva", null, "11912345678", null, null)));

        try (var st = comoOwner().createStatement();
             var rs = st.executeQuery("""
                     select depois::text from auditoria where acao = 'CLIENTE_ALTERADO'
                     """)) {
            assertThat(rs.next()).isTrue();
            String trilha = rs.getString(1);
            assertThat(trilha).contains("telefone");
            assertThat(trilha).doesNotContain("98765").doesNotContain("Maria");
        }
    }

    @Test
    @DisplayName("sem cliente:write, corrigir é recusado no caso de uso")
    @WithMockUser(authorities = Permissao.CLIENTE_LER)
    void sem_permissao_nao_corrige() {
        UUID tenant = salao();

        assertThatThrownBy(() -> TenantContext.executar(tenant, () -> atualizar.executar(
                UUID.randomUUID(), 0, DadosDoCliente.rapido("Maria", null))))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("não corrige ficha de outro salão")
    void isolamento_entre_tenants() {
        UUID salaoA = salao();
        UUID salaoB = salao();
        var daA = TenantContext.obter(salaoA, () -> consultar.executar(
                cadastrar.executar(DadosDoCliente.rapido("Maria Silva", null))));

        var erro = catchThrowable(() -> TenantContext.executar(salaoB, () -> atualizar.executar(
                daA.id(), daA.versao(), DadosDoCliente.rapido("Invadida", null))));

        assertThat(codigoDe(erro)).isEqualTo("ER-CLI-CLIENTE_NAO_ENCONTRADO");
    }
}
