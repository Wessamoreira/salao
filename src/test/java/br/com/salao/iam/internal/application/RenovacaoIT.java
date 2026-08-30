package br.com.salao.iam.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.iam.api.SessaoIniciada;
import br.com.salao.iam.internal.domain.SegredoOpaco;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** RT-IAM-003 — refresh rotativo com detecção de reuso. */
class RenovacaoIT extends AbstractPostgresIT {

    private static final String SENHA = "senha-bem-comprida-1";

    /**
     * Tolerância zero: o teste precisa que reapresentar um token conte como reuso de imediato.
     * Com os 10s de produção, distinguir reuso de reenvio exigiria esperar o relógio.
     */
    @DynamicPropertySource
    static void semTolerancia(DynamicPropertyRegistry registro) {
        registro.add("app.auth.refresh.tolerancia-de-reenvio", () -> "PT0S");
    }

    @Autowired
    private ProvisionarEstabelecimentoUseCase provisionar;

    @Autowired
    private AutenticarUseCase autenticar;

    @Autowired
    private RenovarAcessoUseCase renovar;

    private SessaoIniciada entrar() {
        provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão", null, "Ana", "ana@salao.test", SENHA));
        return autenticar.executar(new AutenticarCommand("ana@salao.test", SENHA));
    }

    private String codigoDe(Throwable e) {
        return ((ErroDeDominio) e).codigo().codigo();
    }

    @Test
    @DisplayName("a renovação entrega um par novo e invalida o refresh usado")
    void rotaciona_e_invalida_o_anterior() {
        var sessao = entrar();

        var renovada = renovar.executar(sessao.refresh(), "10.0.0.1", "teste");

        assertThat(renovada.refresh())
                .as("uso único: o refresh nunca se repete")
                .isNotEqualTo(sessao.refresh());
        assertThat(renovada.acesso().token()).isNotBlank();

        assertThatThrownBy(() -> renovar.executar(sessao.refresh(), "10.0.0.1", "teste"))
                .isInstanceOf(ErroDeDominio.class);
    }

    @Test
    @DisplayName("o novo refresh continua funcionando, em cadeia")
    void cadeia_de_rotacoes() {
        var atual = entrar();
        for (int i = 0; i < 3; i++) {
            atual = renovar.executar(atual.refresh(), "10.0.0.1", "teste");
        }
        assertThat(atual.acesso().token()).isNotBlank();
    }

    @Test
    @DisplayName("reapresentar um refresh já rotacionado derruba a família inteira")
    void reuso_revoga_a_familia() throws SQLException {
        // Um token de uso único que reaparece só tem uma explicação: duas partes têm o mesmo
        // token. Não dá para saber qual é a legítima, então as duas perdem.
        var sessao = entrar();
        var renovada = renovar.executar(sessao.refresh(), "10.0.0.1", "teste");

        var erro = catchThrowable(() -> renovar.executar(sessao.refresh(), "10.0.0.2", "ladrao"));

        assertThat(codigoDe(erro)).isEqualTo("ER-IAM-SESSAO_EXPIRADA");
        assertThat(tokensRevogados())
                .as("a família inteira, não só o token reapresentado")
                .isEqualTo(2);

        assertThatThrownBy(() -> renovar.executar(renovada.refresh(), "10.0.0.1", "teste"))
                .as("o token que estava legitimamente em uso também cai")
                .isInstanceOf(ErroDeDominio.class);
    }

    @Test
    @DisplayName("um login novo abre outra família e não é afetado pelo reuso da primeira")
    void familias_sao_independentes() {
        // Entrar de novo no computador não pode derrubar a sessão do celular.
        var primeira = entrar();
        var segunda = autenticar.executar(new AutenticarCommand("ana@salao.test", SENHA));
        renovar.executar(primeira.refresh(), "10.0.0.1", "teste");

        catchThrowable(() -> renovar.executar(primeira.refresh(), "10.0.0.2", "ladrao"));

        var aindaValida = renovar.executar(segunda.refresh(), "10.0.0.1", "teste");
        assertThat(aindaValida.acesso().token()).isNotBlank();
    }

    @Test
    @DisplayName("refresh desconhecido é recusado com o mesmo código dos demais")
    void refresh_desconhecido() {
        entrar();

        var erro = catchThrowable(() -> renovar.executar(SegredoOpaco.gerar(), null, null));

        assertThat(codigoDe(erro)).isEqualTo("ER-IAM-SESSAO_EXPIRADA");
    }

    @Test
    @DisplayName("refresh expirado é recusado")
    void refresh_expirado() throws SQLException {
        var sessao = entrar();
        vencerTodos();

        assertThatThrownBy(() -> renovar.executar(sessao.refresh(), null, null))
                .isInstanceOf(ErroDeDominio.class);
    }

    @Test
    @DisplayName("desativar o usuário corta a renovação e revoga a família")
    void usuario_desativado_nao_renova() throws SQLException {
        // O access token não é revogável; cortar aqui garante que em no máximo 15 minutos
        // o acesso acaba de fato.
        var sessao = entrar();
        desativarUsuario();

        assertThatThrownBy(() -> renovar.executar(sessao.refresh(), null, null))
                .isInstanceOf(ErroDeDominio.class);
        assertThat(tokensRevogados()).isPositive();
    }

    @Test
    @DisplayName("duas renovações simultâneas: exatamente uma vence, e a família sobrevive")
    void corrida_e_arbitrada_pelo_banco() throws Exception {
        // Quem arbitra é o UPDATE condicional, não a aplicação. E perder a corrida NÃO pode
        // ser tratado como vazamento: seria punir rede instável derrubando a sessão.
        var sessao = entrar();
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> tentativa = () -> {
                try {
                    return renovar.executar(sessao.refresh(), "10.0.0.1", "teste");
                } catch (RuntimeException e) {
                    return e;
                }
            };
            var primeira = executor.submit(tentativa);
            var segunda = executor.submit(tentativa);

            var resultados = java.util.List.of(primeira.get(30, TimeUnit.SECONDS),
                    segunda.get(30, TimeUnit.SECONDS));
            long vencedoras = resultados.stream().filter(r -> r instanceof SessaoIniciada).count();

            assertThat(vencedoras).isEqualTo(1);
            assertThat(tokensRevogados())
                    .as("corrida não é vazamento: a família não pode ser derrubada")
                    .isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    private long tokensRevogados() throws SQLException {
        try (var st = comoOwner().createStatement();
             var rs = st.executeQuery(
                     "select count(*) from refresh_token where revogado_em is not null")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void vencerTodos() throws SQLException {
        try (var st = comoOwner().createStatement()) {
            st.execute("update refresh_token set expira_em = now() - interval '1 day'");
        }
    }

    private void desativarUsuario() throws SQLException {
        try (var st = comoOwner().createStatement()) {
            st.execute("update usuario set ativo = false");
        }
    }
}
