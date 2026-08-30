package br.com.salao.iam.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.salao.iam.api.SessaoIniciada;
import br.com.salao.iam.api.ResultadoDeAutenticacao;
import br.com.salao.iam.api.SessaoIniciada;
import br.com.salao.iam.internal.domain.SegredoOpaco;
import br.com.salao.iam.internal.infra.PurgadorDeRefreshTokens;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** RT-IAM-004 — logout e revogação de sessão. */
class EncerramentoIT extends AbstractPostgresIT {

    private static final String SENHA = "senha-bem-comprida-1";

    @Autowired
    private ProvisionarEstabelecimentoUseCase provisionar;

    @Autowired
    private AutenticarUseCase autenticar;

    @Autowired
    private RenovarAcessoUseCase renovar;

    @Autowired
    private EncerrarSessaoUseCase encerrar;

    @Autowired
    private PurgadorDeRefreshTokens purgador;

    private UUID salao(String email) {
        return provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão", null, "Ana", email, SENHA));
    }

    private SessaoIniciada entrar(String email) {
        return sessaoDe(new AutenticarCommand(email, SENHA));
    }

    @Test
    @DisplayName("o logout encerra a sessão: o refresh para de funcionar")
    void logout_encerra_a_sessao() {
        salao("ana@salao.test");
        var sessao = entrar("ana@salao.test");

        encerrar.encerrar(sessao.refresh());

        assertThatThrownBy(() -> renovar.executar(sessao.refresh(), null, null))
                .isInstanceOf(ErroDeDominio.class)
                .extracting(e -> ((ErroDeDominio) e).codigo().codigo())
                .isEqualTo("ER-IAM-SESSAO_EXPIRADA");
    }

    @Test
    @DisplayName("o logout não derruba a sessão dos outros dispositivos")
    void logout_e_por_dispositivo() {
        // Sair no computador do salão não pode desconectar o celular: são famílias distintas.
        salao("ana@salao.test");
        var computador = entrar("ana@salao.test");
        var celular = entrar("ana@salao.test");

        encerrar.encerrar(computador.refresh());

        assertThatCode(() -> renovar.executar(celular.refresh(), null, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("logout nunca falha: token desconhecido, vazio ou nulo terminam em sucesso")
    void logout_nunca_falha() {
        // Não há nada que o usuário possa fazer a respeito, e a tela diria "não foi possível
        // sair" com o cookie já apagado. Além disso, responder diferente para token válido e
        // inválido faria do logout um oráculo para testar tokens.
        salao("ana@salao.test");

        assertThatCode(() -> encerrar.encerrar(SegredoOpaco.gerar())).doesNotThrowAnyException();
        assertThatCode(() -> encerrar.encerrar("")).doesNotThrowAnyException();
        assertThatCode(() -> encerrar.encerrar(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("logout de uma sessão já encerrada continua terminando em sucesso")
    void logout_e_idempotente() {
        salao("ana@salao.test");
        var sessao = entrar("ana@salao.test");
        encerrar.encerrar(sessao.refresh());

        assertThatCode(() -> encerrar.encerrar(sessao.refresh())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("sair de todos os dispositivos derruba todas as famílias do usuário")
    void logout_de_todos() throws SQLException {
        UUID tenant = salao("ana@salao.test");
        var computador = entrar("ana@salao.test");
        var celular = entrar("ana@salao.test");
        UUID usuarioId = computador.acesso().usuarioId();

        int revogados = TenantContext.obter(tenant, () -> encerrar.encerrarTodas(usuarioId));

        assertThat(revogados).isEqualTo(2);
        assertThatThrownBy(() -> renovar.executar(computador.refresh(), null, null))
                .isInstanceOf(ErroDeDominio.class);
        assertThatThrownBy(() -> renovar.executar(celular.refresh(), null, null))
                .isInstanceOf(ErroDeDominio.class);
    }

    @Test
    @DisplayName("sair de todos não alcança outro estabelecimento")
    void logout_de_todos_respeita_o_tenant() {
        salao("ana@salao.test");
        UUID outro = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Outro", null, "Bia", "bia@salao.test", SENHA));
        var sessaoDaAna = entrar("ana@salao.test");
        var sessaoDaBia = entrar("bia@salao.test");

        TenantContext.executar(outro,
                () -> encerrar.encerrarTodas(sessaoDaBia.acesso().usuarioId()));

        assertThatCode(() -> renovar.executar(sessaoDaAna.refresh(), null, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a purga remove o que venceu há muito e preserva o resto")
    void purga_respeita_a_retencao() throws SQLException {
        salao("ana@salao.test");
        entrar("ana@salao.test");
        vencerHa(Duration.ofDays(60));
        entrar("ana@salao.test");

        int removidos = purgador.executar(Duration.ofDays(30));

        assertThat(removidos).as("só o que venceu além da retenção").isEqualTo(1);
        assertThat(totalDeTokens()).isEqualTo(1);
    }

    private void vencerHa(Duration idade) throws SQLException {
        try (var ps = comoOwner().prepareStatement(
                "update refresh_token set expira_em = now() - cast(? as interval)")) {
            ps.setString(1, idade.toSeconds() + " seconds");
            ps.executeUpdate();
        }
    }

    private long totalDeTokens() throws SQLException {
        try (var st = comoOwner().createStatement();
             var rs = st.executeQuery("select count(*) from refresh_token")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** O login agora tem dois desfechos; estes testes cobrem o caminho sem segundo fator. */
    private SessaoIniciada sessaoDe(AutenticarCommand comando) {
        var resultado = autenticar.executar(comando);
        if (resultado instanceof ResultadoDeAutenticacao.Autenticado autenticado) {
            return autenticado.sessao();
        }
        throw new IllegalStateException("segundo fator inesperado neste cenário");
    }
}
