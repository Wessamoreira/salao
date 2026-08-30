package br.com.salao.iam.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.internal.domain.PoliticaDeBloqueio;
import br.com.salao.iam.internal.infra.EmissorDeTokenJwt;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** RT-IAM-002 — login. */
class AutenticacaoIT extends AbstractPostgresIT {

    private static final String SENHA = "senha-bem-comprida-1";

    @Autowired
    private ProvisionarEstabelecimentoUseCase provisionar;

    @Autowired
    private AutenticarUseCase autenticar;

    @Autowired
    private JwtDecoder decodificador;

    private UUID provisionarCom(String email) {
        return provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão", null, "Ana", email, SENHA));
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    @Test
    @DisplayName("o admin criado no provisionamento consegue entrar")
    void admin_do_provisionamento_entra() {
        UUID estabelecimento = provisionarCom("ana@salao.test");

        var token = autenticar.executar(new AutenticarCommand("ana@salao.test", SENHA));

        assertThat(token.acesso().estabelecimentoId()).isEqualTo(estabelecimento);
        assertThat(token.acesso().perfil()).isEqualTo(Perfil.ADMIN);
        assertThat(token.acesso().expiraEm()).isAfter(java.time.Instant.now());
        assertThat(token.refresh()).as("o login abre uma família de refresh").isNotBlank();
    }

    @Test
    @DisplayName("o token carrega o estabelecimento, que é de onde sai o tenant de cada requisição")
    void token_carrega_o_estabelecimento() {
        UUID estabelecimento = provisionarCom("ana@salao.test");

        var token = autenticar.executar(new AutenticarCommand("ana@salao.test", SENHA));
        var jwt = decodificador.decode(token.acesso().token());

        assertThat(jwt.getClaimAsString(EmissorDeTokenJwt.CLAIM_ESTABELECIMENTO))
                .isEqualTo(estabelecimento.toString());
        assertThat(jwt.getClaimAsString(EmissorDeTokenJwt.CLAIM_PERFIL)).isEqualTo("ADMIN");
        assertThat(jwt.getId()).as("jti permite revogar um token específico depois").isNotBlank();
    }

    @Test
    @DisplayName("o e-mail é case-insensitive")
    void email_case_insensitive() {
        provisionarCom("ana@salao.test");

        var token = autenticar.executar(new AutenticarCommand("  ANA@Salao.TEST ", SENHA));

        assertThat(token).isNotNull();
    }

    @Test
    @DisplayName("e-mail inexistente e senha errada devolvem o mesmo código")
    void nao_permite_enumerar_usuarios() {
        // Distinguir os dois entregaria de graça a resposta para "este e-mail existe aqui?".
        provisionarCom("ana@salao.test");

        var senhaErrada = org.assertj.core.api.Assertions.catchThrowable(
                () -> autenticar.executar(new AutenticarCommand("ana@salao.test", "errada-12345")));
        var emailInexistente = org.assertj.core.api.Assertions.catchThrowable(
                () -> autenticar.executar(new AutenticarCommand("ninguem@salao.test", SENHA)));

        assertThat(codigoDe(senhaErrada)).isEqualTo("ER-IAM-CREDENCIAIS_INVALIDAS");
        assertThat(codigoDe(emailInexistente)).isEqualTo(codigoDe(senhaErrada));
        assertThat(emailInexistente.getMessage()).isEqualTo(senhaErrada.getMessage());
    }

    @Test
    @DisplayName("falhas consecutivas bloqueiam, e o bloqueio recusa antes de conferir a senha")
    void bloqueia_apos_falhas_consecutivas() {
        provisionarCom("ana@salao.test");

        for (int i = 0; i <= PoliticaDeBloqueio.FALHAS_ANTES_DO_BLOQUEIO; i++) {
            org.assertj.core.api.Assertions.catchThrowable(() -> autenticar.executar(
                    new AutenticarCommand("ana@salao.test", "errada-12345")));
        }

        // Agora nem a senha CORRETA passa: é o bloqueio, não a senha, que decide.
        assertThatThrownBy(() -> autenticar.executar(new AutenticarCommand("ana@salao.test", SENHA)))
                .isInstanceOf(ErroDeDominio.class)
                .extracting(this::codigoDe)
                .isEqualTo("ER-IAM-ACESSO_BLOQUEADO");
    }

    @Test
    @DisplayName("um login bem-sucedido zera o contador de falhas")
    void sucesso_zera_falhas() throws SQLException {
        provisionarCom("ana@salao.test");
        org.assertj.core.api.Assertions.catchThrowable(() -> autenticar.executar(
                new AutenticarCommand("ana@salao.test", "errada-12345")));
        assertThat(falhasDe("ana@salao.test")).isEqualTo(1);

        autenticar.executar(new AutenticarCommand("ana@salao.test", SENHA));

        assertThat(falhasDe("ana@salao.test")).isZero();
    }

    @Test
    @DisplayName("usuário inativo não entra, mesmo com a senha certa")
    void usuario_inativo_nao_entra() throws SQLException {
        provisionarCom("ana@salao.test");
        desativar("ana@salao.test");

        assertThatThrownBy(() -> autenticar.executar(new AutenticarCommand("ana@salao.test", SENHA)))
                .isInstanceOf(ErroDeDominio.class)
                .extracting(this::codigoDe)
                .isEqualTo("ER-IAM-CREDENCIAIS_INVALIDAS");
        assertThat(falhasDe("ana@salao.test"))
                .as("senha correta não conta como falha: o usuário não errou nada")
                .isZero();
    }

    @Test
    @DisplayName("dois estabelecimentos: cada admin entra no seu")
    void logins_nao_se_misturam() {
        UUID a = provisionarCom("ana@salao.test");
        UUID b = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Outro Salão", null, "Bia", "bia@salao.test", SENHA));

        assertThat(autenticar.executar(new AutenticarCommand("ana@salao.test", SENHA))
                .acesso().estabelecimentoId()).isEqualTo(a);
        assertThat(autenticar.executar(new AutenticarCommand("bia@salao.test", SENHA))
                .acesso().estabelecimentoId()).isEqualTo(b);
    }

    private int falhasDe(String email) throws SQLException {
        try (var ps = comoOwner().prepareStatement(
                "select falhas_consecutivas from usuario where email_normalizado = ?")) {
            ps.setString(1, email.toLowerCase());
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void desativar(String email) throws SQLException {
        try (var ps = comoOwner().prepareStatement(
                "update usuario set ativo = false where email_normalizado = ?")) {
            ps.setString(1, email.toLowerCase());
            ps.executeUpdate();
        }
    }
}
