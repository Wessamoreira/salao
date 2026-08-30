package br.com.salao.iam.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.ResultadoDeAutenticacao;
import br.com.salao.iam.internal.application.AutenticarCommand;
import br.com.salao.iam.internal.application.AutenticarUseCase;
import br.com.salao.iam.internal.application.ConsultarCapacidadesUseCase;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** RT-IAM-006 — /me/capabilities. */
class CapacidadesIT extends AbstractPostgresIT {

    private static final String SENHA = "senha-bem-comprida-1";

    @Autowired
    private ProvisionarEstabelecimentoUseCase provisionar;

    @Autowired
    private AutenticarUseCase autenticar;

    @Autowired
    private ConsultarCapacidadesUseCase capacidades;

    private record Conta(UUID tenant, UUID usuarioId) {
    }

    private Conta contaCom(Perfil perfil) throws SQLException {
        UUID tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão da Ana", null, "Ana", "ana@salao.test", SENHA));
        var login = (ResultadoDeAutenticacao.Autenticado)
                autenticar.executar(new AutenticarCommand("ana@salao.test", SENHA));
        UUID usuarioId = login.sessao().acesso().usuarioId();
        if (perfil != Perfil.ADMIN) {
            trocarPerfil(usuarioId, perfil);
        }
        return new Conta(tenant, usuarioId);
    }

    @Test
    @DisplayName("o admin recebe tudo, com o estabelecimento e a exigência de MFA")
    void admin_recebe_tudo() throws SQLException {
        var conta = contaCom(Perfil.ADMIN);

        var c = TenantContext.obter(conta.tenant(),
                () -> capacidades.executar(conta.usuarioId(), conta.tenant()));

        assertThat(c.perfil()).isEqualTo(Perfil.ADMIN);
        assertThat(c.permissoes()).contains(Permissao.CONFIG_GERENCIAR,
                Permissao.FINANCEIRO_LER_TODOS, Permissao.CLIENTE_FICHA_LER);
        assertThat(c.estabelecimento().timezone()).isEqualTo("America/Sao_Paulo");
        assertThat(c.estabelecimento().moeda()).isEqualTo("BRL");
        assertThat(c.mfaObrigatorio()).isTrue();
        assertThat(c.mfaAtivo()).isFalse();
    }

    @Test
    @DisplayName("a recepção não recebe menu de financeiro, e recebe o limite de desconto")
    void recepcao_sem_financeiro() throws SQLException {
        // O limite existe para o front avisar ANTES ("acima de 10% precisa do gerente") sem
        // conhecer a regra: ele só compara com o número que recebeu.
        var conta = contaCom(Perfil.RECEPCAO);

        var c = TenantContext.obter(conta.tenant(),
                () -> capacidades.executar(conta.usuarioId(), conta.tenant()));

        assertThat(c.menus()).extracting(m -> m.id())
                .contains("agenda", "atendimento", "clientes", "estoque")
                .doesNotContain("financeiro", "configuracao");
        assertThat(c.limites()).containsEntry("descontoMaximoPercentual", 10);
        assertThat(c.flags()).containsEntry("podeVerValorDeOutros", false);
        assertThat(c.mfaObrigatorio()).isFalse();
    }

    @Test
    @DisplayName("o profissional recebe menu de financeiro — o dele")
    void profissional_ve_o_proprio_extrato() throws SQLException {
        var conta = contaCom(Perfil.PROFISSIONAL);

        var c = TenantContext.obter(conta.tenant(),
                () -> capacidades.executar(conta.usuarioId(), conta.tenant()));

        assertThat(c.menus()).extracting(m -> m.id()).contains("agenda", "financeiro");
        assertThat(c.flags())
                .containsEntry("podeVerValorDeOutros", false)
                .containsEntry("podeAgendarParaOutros", false)
                .containsEntry("podeVerFichaDoCliente", false);
    }

    @Test
    @DisplayName("as flags descrevem o efeito, não o perfil")
    void flags_descrevem_efeito() throws SQLException {
        // O front pergunta "posso ver valor de outros?", nunca "sou gerente?" — é o que impede
        // a regra de voltar a morar no JavaScript.
        var conta = contaCom(Perfil.GERENTE);

        var c = TenantContext.obter(conta.tenant(),
                () -> capacidades.executar(conta.usuarioId(), conta.tenant()));

        assertThat(c.flags()).containsEntry("podeVerValorDeOutros", true);
        assertThat(c.permissoes()).doesNotContain(Permissao.CONFIG_GERENCIAR);
    }

    private void trocarPerfil(UUID usuarioId, Perfil perfil) throws SQLException {
        try (var ps = comoOwner().prepareStatement(
                "update usuario set perfil = ? where id = ?")) {
            ps.setString(1, perfil.name());
            ps.setObject(2, usuarioId);
            ps.executeUpdate();
        }
    }
}
