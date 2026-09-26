package br.com.salao.iam.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.ResultadoDeAutenticacao;
import br.com.salao.iam.api.SessaoIniciada;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * RT-IAM-009 — o que a tela de acessos precisou que existisse.
 *
 * <p>Corrigir a ficha (RN-IAM-024) e definir a senha de outra pessoa (RN-IAM-025) — as duas metades
 * que a falta de tela mantinha escondidas: um e-mail errado queimava o endereço para sempre, e
 * quem esquecia a senha ficava fora do sistema.
 */
class TelaDeAcessosIT extends AbstractPostgresIT {

    private static final String SENHA = "senha-bem-comprida-1";
    private static final String OUTRA_SENHA = "outra-senha-comprida-2";

    @Autowired
    private ProvisionarEstabelecimentoUseCase provisionar;

    @Autowired
    private AutenticarUseCase autenticar;

    @Autowired
    private CriarUsuarioUseCase criar;

    @Autowired
    private ListarUsuariosUseCase listar;

    @Autowired
    private EditarUsuarioUseCase editar;

    @Autowired
    private DefinirSenhaDoUsuarioUseCase definirSenha;

    @Autowired
    private ListarPerfisUseCase listarPerfis;

    @Autowired
    private RenovarAcessoUseCase renovar;

    private record Salao(UUID tenant, UUID adminId) {
    }

    private Salao salao() {
        UUID tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão", null, "Ana", "ana@salao.test", SENHA));
        return new Salao(tenant, sessao("ana@salao.test", SENHA).acesso().usuarioId());
    }

    private UUID bia(Salao s) {
        return TenantContext.obter(s.tenant(), () ->
                criar.executar("Bia", "bia@salao.test", OUTRA_SENHA, Perfil.RECEPCAO));
    }

    private SessaoIniciada sessao(String email, String senha) {
        return ((ResultadoDeAutenticacao.Autenticado)
                autenticar.executar(new AutenticarCommand(email, senha))).sessao();
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    // ---------------------------------------------------------------- RN-IAM-024

    @Test
    @WithMockUser(authorities = Permissao.USUARIO_GERENCIAR)
    @DisplayName("corrigir só o nome não derruba a sessão de quem está trabalhando")
    void corrigir_nome_nao_derruba_sessao() {
        // Nome não é credencial. Derrubar a recepção no meio do expediente para consertar um
        // acento seria punição por conserto.
        var s = salao();
        UUID bia = bia(s);
        var sessaoDaBia = sessao("bia@salao.test", OUTRA_SENHA);

        TenantContext.executar(s.tenant(), () ->
                editar.executar(bia, "Beatriz Nunes", "bia@salao.test"));

        assertThat(renovar.executar(sessaoDaBia.refresh(), null, null)).isNotNull();
        assertThat(TenantContext.obter(s.tenant(), () -> listar.executar()))
                .extracting(u -> u.nome()).contains("Beatriz Nunes");
    }

    @Test
    @WithMockUser(authorities = Permissao.USUARIO_GERENCIAR)
    @DisplayName("trocar o e-mail encerra as sessões: a credencial de entrada mudou")
    void trocar_email_derruba_sessoes() {
        var s = salao();
        UUID bia = bia(s);
        var sessaoDaBia = sessao("bia@salao.test", OUTRA_SENHA);

        TenantContext.executar(s.tenant(), () ->
                editar.executar(bia, "Bia", "beatriz@salao.test"));

        assertThatThrownBy(() -> renovar.executar(sessaoDaBia.refresh(), null, null))
                .isInstanceOf(ErroDeDominio.class);
        assertThat(sessao("beatriz@salao.test", OUTRA_SENHA).acesso().usuarioId()).isEqualTo(bia);
    }

    @Test
    @WithMockUser(authorities = Permissao.USUARIO_GERENCIAR)
    @DisplayName("mudar só a caixa do e-mail não é troca de credencial")
    void caixa_do_email_nao_derruba_sessao() {
        // "Bia@Salao.test" e "bia@salao.test" são a mesma credencial escrita de outro jeito: o
        // login normaliza antes de procurar.
        var s = salao();
        UUID bia = bia(s);
        var sessaoDaBia = sessao("bia@salao.test", OUTRA_SENHA);

        TenantContext.executar(s.tenant(), () ->
                editar.executar(bia, "Bia", "Bia@Salao.test"));

        assertThat(renovar.executar(sessaoDaBia.refresh(), null, null)).isNotNull();
    }

    @Test
    @WithMockUser(authorities = Permissao.USUARIO_GERENCIAR)
    @DisplayName("e-mail de outra conta é recusado sem dizer onde já existe")
    void email_repetido_nao_diz_onde() {
        var s = salao();
        UUID bia = bia(s);

        var erro = catchThrowable(() -> TenantContext.executar(s.tenant(), () ->
                editar.executar(bia, "Bia", "ana@salao.test")));

        assertThat(codigoDe(erro)).isEqualTo("ER-IAM-EMAIL_JA_CADASTRADO");
        assertThat(erro.getMessage()).doesNotContain("salão").doesNotContain("estabelecimento");
    }

    @Test
    @WithMockUser(authorities = Permissao.AGENDA_LER_TODAS)
    @DisplayName("quem não gerencia usuário não corrige ficha de ninguém")
    void sem_permissao_nao_edita() {
        var s = salao();

        assertThatThrownBy(() -> TenantContext.executar(s.tenant(), () ->
                editar.executar(s.adminId(), "Outro Nome", "outro@salao.test")))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------------------------------------------------------------- RN-IAM-025

    @Test
    @WithMockUser(authorities = Permissao.USUARIO_GERENCIAR)
    @DisplayName("o administrador define a senha de outra pessoa e as sessões dela caem")
    void define_senha_de_outra_pessoa() {
        var s = salao();
        UUID bia = bia(s);
        var sessaoDaBia = sessao("bia@salao.test", OUTRA_SENHA);

        TenantContext.executar(s.tenant(), () ->
                definirSenha.executar(bia, "senha-nova-do-admin-3", s.adminId()));

        assertThatThrownBy(() -> renovar.executar(sessaoDaBia.refresh(), null, null))
                .isInstanceOf(ErroDeDominio.class);
        assertThat(sessao("bia@salao.test", "senha-nova-do-admin-3").acesso().usuarioId())
                .isEqualTo(bia);
    }

    @Test
    @WithMockUser(authorities = Permissao.USUARIO_GERENCIAR)
    @DisplayName("a própria senha não se troca por aqui: o caminho exige a senha atual")
    void nao_define_a_propria_senha() {
        // Sem esta recusa, quem sentasse no computador do balcão com a sessão do dono aberta
        // trocaria a senha do dono sem saber a senha do dono.
        var s = salao();

        var erro = catchThrowable(() -> TenantContext.executar(s.tenant(), () ->
                definirSenha.executar(s.adminId(), "senha-nova-do-admin-3", s.adminId())));

        assertThat(codigoDe(erro)).isEqualTo("ER-IAM-OPERACAO_SOBRE_SI_MESMO");
        assertThat(sessao("ana@salao.test", SENHA)).as("a senha antiga continua valendo")
                .isNotNull();
    }

    @Test
    @WithMockUser(authorities = Permissao.USUARIO_GERENCIAR)
    @DisplayName("senha curta é recusada com o mesmo mínimo do cadastro")
    void senha_curta_recusada() {
        var s = salao();
        UUID bia = bia(s);

        var erro = catchThrowable(() -> TenantContext.executar(s.tenant(), () ->
                definirSenha.executar(bia, "curta", s.adminId())));

        assertThat(codigoDe(erro)).isEqualTo("ER-IAM-DADOS_INVALIDOS");
    }

    @Test
    @WithMockUser(authorities = Permissao.AGENDA_LER_TODAS)
    @DisplayName("quem não gerencia usuário não define senha de ninguém")
    void sem_permissao_nao_define_senha() {
        var s = salao();

        assertThatThrownBy(() -> TenantContext.executar(s.tenant(), () ->
                definirSenha.executar(UUID.randomUUID(), "senha-nova-do-admin-3", s.adminId())))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------------------------------------------------------------- catálogo de perfis

    @Test
    @WithMockUser(authorities = Permissao.USUARIO_GERENCIAR)
    @DisplayName("o catálogo de perfis chega pronto para a tela desenhar")
    void catalogo_de_perfis() {
        assertThat(listarPerfis.executar())
                .anySatisfy(p -> {
                    assertThat(p.perfil()).isEqualTo(Perfil.GERENTE);
                    assertThat(p.exigeMfa()).isTrue();
                    assertThat(p.podeFazer()).contains("ver o dinheiro do salão inteiro");
                })
                .anySatisfy(p -> {
                    assertThat(p.perfil()).isEqualTo(Perfil.RECEPCAO);
                    assertThat(p.exigeMfa()).isFalse();
                    assertThat(p.podeFazer()).doesNotContain("ver o dinheiro do salão inteiro");
                });
    }

    @Test
    @WithMockUser(authorities = Permissao.AGENDA_LER_TODAS)
    @DisplayName("o catálogo é mapa da casa: só quem administra a casa o recebe")
    void catalogo_pede_permissao() {
        assertThatThrownBy(() -> listarPerfis.executar())
                .isInstanceOf(AccessDeniedException.class);
    }
}
