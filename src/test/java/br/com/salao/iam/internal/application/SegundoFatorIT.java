package br.com.salao.iam.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.iam.api.ResultadoDeAutenticacao;
import br.com.salao.iam.internal.domain.Totp;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** RT-IAM-005 — segundo fator (TOTP). */
class SegundoFatorIT extends AbstractPostgresIT {

    private static final String SENHA = "senha-bem-comprida-1";

    @Autowired
    private ProvisionarEstabelecimentoUseCase provisionar;

    @Autowired
    private AutenticarUseCase autenticar;

    @Autowired
    private SegundoFatorUseCase segundoFator;

    private record Conta(UUID tenant, UUID usuarioId, String segredo) {
    }

    /** Provisiona, inscreve e confirma o MFA — o estado de quem já ativou o segundo fator. */
    private Conta contaComMfa() {
        UUID tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão da Ana", null, "Ana", "ana@salao.test", SENHA));
        var login = autenticar.executar(new AutenticarCommand("ana@salao.test", SENHA));
        UUID usuarioId = ((ResultadoDeAutenticacao.Autenticado) login).sessao().acesso().usuarioId();

        var inscricao = TenantContext.obter(tenant,
                () -> segundoFator.inscrever(tenant, usuarioId));
        TenantContext.executar(tenant, () ->
                segundoFator.confirmar(tenant, usuarioId, codigoAgora(inscricao.segredo())));
        return new Conta(tenant, usuarioId, inscricao.segredo());
    }

    private String codigoAgora(String segredo) {
        return Totp.gerar(segredo, Totp.contadorDe(Instant.now()));
    }

    private String codigoDaJanela(String segredo, long deslocamento) {
        return Totp.gerar(segredo, Totp.contadorDe(Instant.now()) + deslocamento);
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    @Test
    @DisplayName("inscrever não ativa o MFA: só confirmar ativa")
    void inscricao_nao_ativa() {
        // Ativar na inscrição trancaria para fora quem digitasse o segredo errado no aplicativo,
        // e o único jeito de sair seria um administrador.
        UUID tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão", null, "Ana", "ana@salao.test", SENHA));
        var login = autenticar.executar(new AutenticarCommand("ana@salao.test", SENHA));
        UUID usuarioId = ((ResultadoDeAutenticacao.Autenticado) login).sessao().acesso().usuarioId();

        TenantContext.executar(tenant,
                () -> segundoFator.inscrever(tenant, usuarioId));

        assertThat(autenticar.executar(new AutenticarCommand("ana@salao.test", SENHA)))
                .as("ainda entra direto: a inscrição está pendente")
                .isInstanceOf(ResultadoDeAutenticacao.Autenticado.class);
    }

    @Test
    @DisplayName("com MFA ativo, o login devolve desafio e nenhum token de acesso")
    void login_com_mfa_devolve_desafio() {
        var conta = contaComMfa();

        var resultado = autenticar.executar(new AutenticarCommand("ana@salao.test", SENHA));

        assertThat(resultado).isInstanceOf(ResultadoDeAutenticacao.SegundoFatorPendente.class);
        var pendente = (ResultadoDeAutenticacao.SegundoFatorPendente) resultado;
        assertThat(pendente.desafio()).isNotBlank();
        assertThat(pendente.expiraEm()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("o desafio mais o código abrem a sessão")
    void desafio_com_codigo_abre_sessao() {
        var conta = contaComMfa();
        var pendente = (ResultadoDeAutenticacao.SegundoFatorPendente)
                autenticar.executar(new AutenticarCommand("ana@salao.test", SENHA));

        var sessao = segundoFator.concluirLogin(pendente.desafio(),
                codigoDaJanela(conta.segredo(), 1));

        assertThat(sessao.acesso().token()).isNotBlank();
        assertThat(sessao.refresh()).isNotBlank();
    }

    @Test
    @DisplayName("o mesmo código não vale duas vezes, mesmo dentro dos 30 segundos")
    void codigo_nao_pode_ser_reapresentado() {
        // Um TOTP interceptado vale 30 segundos. Sem o contador registrado, quem o capturasse
        // poderia reapresentá-lo dentro da janela.
        var conta = contaComMfa();
        String codigo = codigoDaJanela(conta.segredo(), 1);
        var primeiro = (ResultadoDeAutenticacao.SegundoFatorPendente)
                autenticar.executar(new AutenticarCommand("ana@salao.test", SENHA));
        segundoFator.concluirLogin(primeiro.desafio(), codigo);

        var segundo = (ResultadoDeAutenticacao.SegundoFatorPendente)
                autenticar.executar(new AutenticarCommand("ana@salao.test", SENHA));

        assertThatThrownBy(() -> segundoFator.concluirLogin(segundo.desafio(), codigo))
                .isInstanceOf(ErroDeDominio.class)
                .extracting(this::codigoDe)
                .isEqualTo("ER-IAM-SEGUNDO_FATOR_INVALIDO");
    }

    @Test
    @DisplayName("um access token comum não serve de desafio")
    void access_token_nao_serve_de_desafio() {
        // Sem essa checagem, uma sessão antiga ainda válida permitiria pular a senha.
        UUID tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão", null, "Bia", "bia@salao.test", SENHA));
        var login = (ResultadoDeAutenticacao.Autenticado)
                autenticar.executar(new AutenticarCommand("bia@salao.test", SENHA));

        var erro = catchThrowable(() ->
                segundoFator.concluirLogin(login.sessao().acesso().token(), "000000"));

        assertThat(codigoDe(erro)).isEqualTo("ER-IAM-SEGUNDO_FATOR_INVALIDO");
    }

    @Test
    @DisplayName("código de recuperação funciona uma vez e só uma")
    void codigo_de_recuperacao_e_de_uso_unico() {
        UUID tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão", null, "Ana", "ana@salao.test", SENHA));
        var login = (ResultadoDeAutenticacao.Autenticado)
                autenticar.executar(new AutenticarCommand("ana@salao.test", SENHA));
        UUID usuarioId = login.sessao().acesso().usuarioId();

        var inscricao = TenantContext.obter(tenant,
                () -> segundoFator.inscrever(tenant, usuarioId));
        List<String> recuperacao = TenantContext.obter(tenant, () ->
                segundoFator.confirmar(tenant, usuarioId, codigoAgora(inscricao.segredo())));

        assertThat(recuperacao).hasSize(10).doesNotHaveDuplicates();

        var pendente = (ResultadoDeAutenticacao.SegundoFatorPendente)
                autenticar.executar(new AutenticarCommand("ana@salao.test", SENHA));
        var sessao = segundoFator.concluirLogin(pendente.desafio(), recuperacao.get(0));
        assertThat(sessao.acesso().token()).isNotBlank();

        var outro = (ResultadoDeAutenticacao.SegundoFatorPendente)
                autenticar.executar(new AutenticarCommand("ana@salao.test", SENHA));
        assertThatThrownBy(() -> segundoFator.concluirLogin(outro.desafio(), recuperacao.get(0)))
                .as("uso único")
                .isInstanceOf(ErroDeDominio.class);
    }

    @Test
    @DisplayName("desativar exige código válido")
    void desativar_exige_codigo() {
        // Sem isso, bastaria uma sessão aberta — ou roubada — para remover o segundo fator.
        var conta = contaComMfa();

        assertThatThrownBy(() -> TenantContext.executar(conta.tenant(),
                () -> segundoFator.desativar(conta.usuarioId(), "000000")))
                .isInstanceOf(ErroDeDominio.class);

        TenantContext.executar(conta.tenant(), () ->
                segundoFator.desativar(conta.usuarioId(), codigoDaJanela(conta.segredo(), 1)));

        assertThat(autenticar.executar(new AutenticarCommand("ana@salao.test", SENHA)))
                .as("sem MFA, o login volta a ser direto")
                .isInstanceOf(ResultadoDeAutenticacao.Autenticado.class);
    }

    @Test
    @DisplayName("o segredo é guardado cifrado no banco")
    void segredo_nao_fica_em_claro() throws SQLException {
        // Um dump vazado não pode entregar o segundo fator: quem obtém o segredo gera códigos
        // válidos para sempre.
        var conta = contaComMfa();

        try (var st = comoOwner().createStatement();
             var rs = st.executeQuery(
                     "select encode(segredo_cifrado, 'escape') from mfa_credencial")) {
            rs.next();
            assertThat(rs.getString(1)).doesNotContain(conta.segredo());
        }
    }
}
