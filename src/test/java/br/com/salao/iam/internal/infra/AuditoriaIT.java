package br.com.salao.iam.internal.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.iam.api.ResultadoDeAutenticacao;
import br.com.salao.iam.internal.application.AlterarPerfilDoUsuarioUseCase;
import br.com.salao.iam.internal.application.AutenticarCommand;
import br.com.salao.iam.internal.application.AutenticarUseCase;
import br.com.salao.iam.internal.application.CriarUsuarioUseCase;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** RT-IAM-008 — auditoria append-only. */
class AuditoriaIT extends AbstractPostgresIT {

    private static final String SENHA = "senha-bem-comprida-1";

    @Autowired
    private AuditoriaApi auditoria;

    @Autowired
    private ProvisionarEstabelecimentoUseCase provisionar;

    @Autowired
    private AutenticarUseCase autenticar;

    @Autowired
    private CriarUsuarioUseCase criar;

    @Autowired
    private AlterarPerfilDoUsuarioUseCase alterarPerfil;

    @Autowired
    private PurgadorDeAuditoria purgador;

    @Autowired
    private PlatformTransactionManager gerenciadorDeTransacao;

    @Autowired
    private javax.sql.DataSource dataSource;

    private record Salao(UUID tenant, UUID adminId) {
    }

    private Salao salao() {
        UUID tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão", null, "Ana", "ana@salao.test", SENHA));
        var login = (ResultadoDeAutenticacao.Autenticado)
                autenticar.executar(new AutenticarCommand("ana@salao.test", SENHA));
        return new Salao(tenant, login.sessao().acesso().usuarioId());
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(gerenciadorDeTransacao);
    }

    @Test
    @DisplayName("a trilha é imutável: a aplicação não tem UPDATE nem DELETE")
    void trilha_e_imutavel() throws SQLException {
        // Imutabilidade por PERMISSÃO, não por convenção. Convenção depende de ninguém escrever
        // o UPDATE; a permissão revogada impede que ele funcione.
        var s = salao();
        registrar(s.tenant(), "TESTE", UUID.randomUUID());

        assertThatThrownBy(() -> comoAplicacao(s.tenant(),
                "update auditoria set acao = 'ADULTERADO'"))
                .as("a role da aplicação não pode alterar a trilha")
                // Na causa, e não na mensagem: o Spring embrulha o erro do driver. Assertar a
                // razão importa — falhar por outro motivo qualquer também passaria num
                // assertThatThrownBy genérico, e o teste não provaria nada.
                .hasStackTraceContaining("permission denied");

        assertThatThrownBy(() -> comoAplicacao(s.tenant(), "delete from auditoria"))
                .hasStackTraceContaining("permission denied");

        assertThat(acoes(s.tenant()))
                .as("o registro continua lá, intacto")
                .containsExactly("TESTE");
    }

    /** Executa pela conexão de {@code salao_app} — exatamente a role que não pode. */
    private void comoAplicacao(UUID tenant, String sql) {
        TenantContext.executar(tenant, () -> tx().executeWithoutResult(t ->
                org.springframework.jdbc.core.simple.JdbcClient.create(dataSource)
                        .sql(sql).update()));
    }

    @Test
    @DisplayName("registro de alteração revertida não fica na trilha")
    void rollback_nao_deixa_rastro() throws SQLException {
        // Trilha que registra o que não aconteceu é pior que trilha ausente: alguém vai
        // acreditar nela.
        var s = salao();

        assertThatThrownBy(() -> TenantContext.executar(s.tenant(), () ->
                tx().executeWithoutResult(t -> {
                    auditoria.registrar(new RegistroDeAuditoria("TESTE", "usuario",
                            UUID.randomUUID(), null, Map.of("x", 1)));
                    throw new IllegalStateException("negócio falhou depois de auditar");
                }))).isInstanceOf(IllegalStateException.class);

        assertThat(contar(s.tenant())).isZero();
    }

    @Test
    @DisplayName("auditar fora de transação falha, em vez de gravar por conta própria")
    void exige_transacao() {
        // Propagation.MANDATORY: se abrisse transação própria, a trilha registraria alterações
        // que o rollback desfez. Falhar aqui avisa quem esqueceu, na hora.
        var s = salao();

        assertThatThrownBy(() -> TenantContext.executar(s.tenant(), () ->
                auditoria.registrar(new RegistroDeAuditoria("TESTE", "usuario",
                        UUID.randomUUID(), null, null))))
                .isNotNull();
    }

    @Test
    @DisplayName("campos sensíveis são omitidos mesmo se alguém os passar")
    void campos_sensiveis_sao_omitidos() throws SQLException {
        // A trilha é retida por anos e lida por gente. Um hash de senha ali viraria dado
        // sensível de longa duração que ninguém revisaria depois.
        var s = salao();
        UUID alvo = UUID.randomUUID();

        TenantContext.executar(s.tenant(), () -> tx().executeWithoutResult(t ->
                auditoria.registrar(new RegistroDeAuditoria("TESTE", "usuario", alvo, null,
                        Map.of("nome", "Ana", "senha_hash", "$argon2id$muito$secreto")))));

        assertThat(depoisDe(alvo))
                .contains("Ana")
                .doesNotContain("argon2id")
                .contains("[omitido]");
    }

    @Test
    @WithMockUser(authorities = Permissao.USUARIO_GERENCIAR)
    @DisplayName("as operações de gestão deixam rastro com antes e depois")
    void gestao_deixa_rastro() throws SQLException {
        // Fecha a pendência de RT-IAM-007: promover e desativar só geravam log de aplicação,
        // que não serve como trilha.
        var s = salao();

        UUID bia = TenantContext.obter(s.tenant(), () ->
                criar.executar("Bia", "bia@salao.test", "outra-senha-comprida-2",
                        Perfil.RECEPCAO));
        TenantContext.executar(s.tenant(), () ->
                alterarPerfil.executar(bia, Perfil.GERENTE, s.adminId()));

        var acoes = acoes(s.tenant());
        assertThat(acoes).contains("USUARIO_CRIADO", "PERFIL_ALTERADO");
        assertThat(depoisDe(bia)).contains("GERENTE");
    }

    @Test
    @DisplayName("a retenção é maior para agenda e financeiro que para o resto")
    void retencao_por_criticidade() throws SQLException {
        // Agenda e financeiro respondem a disputa; o resto, passado um ano, não responde nada.
        var s = salao();
        inserirAntiga(s.tenant(), "agendamento", Duration.ofDays(400));
        inserirAntiga(s.tenant(), "usuario", Duration.ofDays(400));

        int removidos = purgador.executar(Duration.ofDays(1825), Duration.ofDays(365));

        assertThat(removidos).isEqualTo(1);
        assertThat(acoes(s.tenant())).hasSize(1);
    }

    private void registrar(UUID tenant, String acao, UUID entidadeId) {
        TenantContext.executar(tenant, () -> tx().executeWithoutResult(t ->
                auditoria.registrar(new RegistroDeAuditoria(acao, "usuario", entidadeId,
                        null, Map.of("x", 1)))));
    }

    private long contar(UUID tenant) throws SQLException {
        try (var st = comoOwner().createStatement();
             var rs = st.executeQuery("select count(*) from auditoria")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private List<String> acoes(UUID tenant) throws SQLException {
        var acoes = new java.util.ArrayList<String>();
        try (var st = comoOwner().createStatement();
             var rs = st.executeQuery("select acao from auditoria order by id")) {
            while (rs.next()) {
                acoes.add(rs.getString(1));
            }
        }
        return acoes;
    }

    private String depoisDe(UUID entidadeId) throws SQLException {
        try (var ps = comoOwner().prepareStatement(
                "select depois::text from auditoria where entidade_id = ? order by id desc")) {
            ps.setObject(1, entidadeId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private void inserirAntiga(UUID tenant, String entidade, Duration idade) throws SQLException {
        try (var ps = comoOwner().prepareStatement("""
                insert into auditoria (estabelecimento_id, ator, acao, entidade, ocorrido_em)
                values (?, 'SISTEMA', 'ANTIGO', ?, now() - cast(? as interval))
                """)) {
            ps.setObject(1, tenant);
            ps.setString(2, entidade);
            ps.setString(3, idade.toSeconds() + " seconds");
            ps.executeUpdate();
        }
    }
}
