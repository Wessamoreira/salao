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

/** RT-IAM-007 — gestão de usuários. */
class GestaoDeUsuariosIT extends AbstractPostgresIT {

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
    private AlterarPerfilDoUsuarioUseCase alterarPerfil;

    @Autowired
    private DefinirAtivacaoDoUsuarioUseCase definirAtivacao;

    @Autowired
    private TrocarSenhaUseCase trocarSenha;

    @Autowired
    private RenovarAcessoUseCase renovar;

    private record Salao(UUID tenant, UUID adminId) {
    }

    private Salao salao() {
        UUID tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão", null, "Ana", "ana@salao.test", SENHA));
        return new Salao(tenant, sessao("ana@salao.test", SENHA).acesso().usuarioId());
    }

    private SessaoIniciada sessao(String email, String senha) {
        return ((ResultadoDeAutenticacao.Autenticado)
                autenticar.executar(new AutenticarCommand(email, senha))).sessao();
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    @Test
    @WithMockUser(authorities = Permissao.USUARIO_GERENCIAR)
    @DisplayName("cria usuário e ele consegue entrar")
    void cria_usuario_que_entra() {
        var s = salao();

        UUID novo = TenantContext.obter(s.tenant(), () ->
                criar.executar("Bia", "bia@salao.test", OUTRA_SENHA, Perfil.RECEPCAO));

        assertThat(novo).isNotNull();
        assertThat(sessao("bia@salao.test", OUTRA_SENHA).acesso().perfil())
                .isEqualTo(Perfil.RECEPCAO);
    }

    @Test
    @DisplayName("sem a permissão, criar usuário é recusado — a autorização está no caso de uso")
    @WithMockUser(authorities = Permissao.AGENDA_LER_TODAS)
    void sem_permissao_nao_cria() {
        // Colocar a autorização no controller deixaria o bot da Fase 4 de fora, porque ele
        // chama o caso de uso diretamente.
        var s = salao();

        assertThatThrownBy(() -> TenantContext.executar(s.tenant(), () ->
                criar.executar("Bia", "bia@salao.test", OUTRA_SENHA, Perfil.RECEPCAO)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(authorities = Permissao.USUARIO_GERENCIAR)
    @DisplayName("e-mail repetido é recusado sem dizer onde já existe")
    void email_repetido() {
        // O índice é global: a colisão pode ser com outro estabelecimento, e contar isso a um
        // administrador revelaria algo sobre um tenant que não é o dele.
        var s = salao();

        var erro = catchThrowable(() -> TenantContext.executar(s.tenant(), () ->
                criar.executar("Outra Ana", "ana@salao.test", OUTRA_SENHA, Perfil.RECEPCAO)));

        assertThat(codigoDe(erro)).isEqualTo("ER-IAM-EMAIL_JA_CADASTRADO");
        assertThat(erro.getMessage()).doesNotContain("salão").doesNotContain("estabelecimento");
    }

    @Test
    @WithMockUser(authorities = Permissao.USUARIO_GERENCIAR)
    @DisplayName("BOT não é perfil atribuível a pessoas")
    void bot_nao_e_atribuivel() {
        // Um usuário BOT criaria o confused deputy que o projeto evita: o bot age em nome de
        // alguém e herda as permissões dessa pessoa.
        var s = salao();

        assertThatThrownBy(() -> TenantContext.executar(s.tenant(), () ->
                criar.executar("Robô", "bot@salao.test", OUTRA_SENHA, Perfil.BOT)))
                .isInstanceOf(ErroDeDominio.class);
    }

    @Test
    @WithMockUser(authorities = Permissao.USUARIO_GERENCIAR)
    @DisplayName("não dá para rebaixar nem desativar a si mesmo")
    void nao_opera_sobre_si_mesmo() {
        var s = salao();

        var rebaixar = catchThrowable(() -> TenantContext.executar(s.tenant(), () ->
                alterarPerfil.executar(s.adminId(), Perfil.RECEPCAO, s.adminId())));
        var desativar = catchThrowable(() -> TenantContext.executar(s.tenant(), () ->
                definirAtivacao.executar(s.adminId(), false, s.adminId())));

        assertThat(codigoDe(rebaixar)).isEqualTo("ER-IAM-OPERACAO_SOBRE_SI_MESMO");
        assertThat(codigoDe(desativar)).isEqualTo("ER-IAM-OPERACAO_SOBRE_SI_MESMO");
    }

    @Test
    @WithMockUser(authorities = Permissao.USUARIO_GERENCIAR)
    @DisplayName("o salão não pode ficar sem administrador")
    void ultimo_administrador_e_protegido() {
        // Sem esta trava, a única saída seria alterar o banco à mão.
        var s = salao();
        UUID segundoAdmin = TenantContext.obter(s.tenant(), () ->
                criar.executar("Bia", "bia@salao.test", OUTRA_SENHA, Perfil.ADMIN));

        // Com dois admins, rebaixar um é permitido.
        TenantContext.executar(s.tenant(), () ->
                alterarPerfil.executar(segundoAdmin, Perfil.GERENTE, s.adminId()));

        // Agora só resta um, e ele não pode ser rebaixado por outro administrador qualquer.
        var erro = catchThrowable(() -> TenantContext.executar(s.tenant(), () ->
                alterarPerfil.executar(s.adminId(), Perfil.GERENTE, segundoAdmin)));

        assertThat(codigoDe(erro)).isEqualTo("ER-IAM-ULTIMO_ADMINISTRADOR");
    }

    @Test
    @WithMockUser(authorities = Permissao.USUARIO_GERENCIAR)
    @DisplayName("desativar encerra as sessões do usuário")
    void desativar_encerra_sessoes() {
        var s = salao();
        UUID bia = TenantContext.obter(s.tenant(), () ->
                criar.executar("Bia", "bia@salao.test", OUTRA_SENHA, Perfil.RECEPCAO));
        var sessaoDaBia = sessao("bia@salao.test", OUTRA_SENHA);

        TenantContext.executar(s.tenant(), () -> definirAtivacao.executar(bia, false, s.adminId()));

        assertThatThrownBy(() -> renovar.executar(sessaoDaBia.refresh(), null, null))
                .isInstanceOf(ErroDeDominio.class);
    }

    @Test
    @WithMockUser(authorities = Permissao.USUARIO_GERENCIAR)
    @DisplayName("rebaixar encerra as sessões: o token antigo carrega o perfil antigo")
    void rebaixar_encerra_sessoes() {
        // Sem isso, o access token com o perfil anterior valeria por até 15 minutos. Numa
        // promoção seria irrelevante; num rebaixamento é a janela que não se quer abrir.
        var s = salao();
        UUID bia = TenantContext.obter(s.tenant(), () ->
                criar.executar("Bia", "bia@salao.test", OUTRA_SENHA, Perfil.GERENTE));
        var sessaoDaBia = sessao("bia@salao.test", OUTRA_SENHA);

        TenantContext.executar(s.tenant(), () ->
                alterarPerfil.executar(bia, Perfil.RECEPCAO, s.adminId()));

        assertThatThrownBy(() -> renovar.executar(sessaoDaBia.refresh(), null, null))
                .isInstanceOf(ErroDeDominio.class);
    }

    @Test
    @WithMockUser(authorities = Permissao.USUARIO_GERENCIAR)
    @DisplayName("trocar a senha exige a atual e encerra todas as sessões")
    void trocar_senha() {
        // Exigir a senha atual protege contra quem senta no computador do salão com a sessão
        // aberta — o cenário mais provável num balcão compartilhado.
        var s = salao();
        var sessaoAntiga = sessao("ana@salao.test", SENHA);

        var erro = catchThrowable(() -> TenantContext.executar(s.tenant(), () ->
                trocarSenha.executar(s.adminId(), "errada-12345", OUTRA_SENHA)));
        assertThat(codigoDe(erro)).isEqualTo("ER-IAM-SENHA_ATUAL_INCORRETA");

        TenantContext.executar(s.tenant(), () ->
                trocarSenha.executar(s.adminId(), SENHA, OUTRA_SENHA));

        assertThatThrownBy(() -> renovar.executar(sessaoAntiga.refresh(), null, null))
                .as("todas as sessões caem, inclusive a de quem trocou")
                .isInstanceOf(ErroDeDominio.class);
        assertThat(sessao("ana@salao.test", OUTRA_SENHA)).isNotNull();
    }

    @Test
    @WithMockUser(authorities = Permissao.USUARIO_GERENCIAR)
    @DisplayName("a listagem mostra apenas os usuários do estabelecimento")
    void listagem_e_isolada() {
        var s = salao();
        TenantContext.executar(s.tenant(), () ->
                criar.executar("Bia", "bia@salao.test", OUTRA_SENHA, Perfil.RECEPCAO));
        provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Outro Salão", null, "Cida", "cida@outro.test", SENHA));

        var usuarios = TenantContext.obter(s.tenant(), () -> listar.executar());

        assertThat(usuarios).extracting(u -> u.email())
                .containsExactlyInAnyOrder("ana@salao.test", "bia@salao.test");
    }
}
