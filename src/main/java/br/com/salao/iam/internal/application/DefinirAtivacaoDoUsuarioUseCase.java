package br.com.salao.iam.internal.application;

import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.iam.internal.infra.CredenciaisJdbc;
import br.com.salao.iam.internal.infra.RefreshTokensJdbc;
import br.com.salao.iam.internal.infra.UsuariosJdbc;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.erro.ErrosDaInfra;
import br.com.salao.shared.tempo.Relogio;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;

/**
 * RT-IAM-007 — ativar ou desativar um usuário.
 *
 * <p>Desativar, e não apagar: o histórico aponta para quem executou cada serviço e cada comanda.
 * Apagar o usuário quebraria esse rastro — e o rastro é o que sustenta o extrato de comissão que
 * o profissional recebe.
 */
public class DefinirAtivacaoDoUsuarioUseCase {

    private static final Logger log =
            LoggerFactory.getLogger(DefinirAtivacaoDoUsuarioUseCase.class);

    private final UsuariosJdbc usuarios;
    private final CredenciaisJdbc credenciais;
    private final RefreshTokensJdbc tokens;
    private final Relogio relogio;
    private final AuditoriaApi auditoria;

    public DefinirAtivacaoDoUsuarioUseCase(UsuariosJdbc usuarios, CredenciaisJdbc credenciais,
                                           RefreshTokensJdbc tokens, Relogio relogio,
                                           AuditoriaApi auditoria) {
        this.usuarios = usuarios;
        this.credenciais = credenciais;
        this.tokens = tokens;
        this.relogio = relogio;
        this.auditoria = auditoria;
    }

    @PreAuthorize("hasAuthority('" + Permissao.USUARIO_GERENCIAR + "')")
    @Transactional
    public void executar(UUID usuarioId, boolean ativo, UUID quemPede) {
        var atual = credenciais.porId(usuarioId).orElseThrow(() ->
                new ErroDeDominio(ErrosDaInfra.NAO_ENCONTRADO, "Usuário não encontrado."));

        if (!ativo) {
            GestaoDeUsuarios.recusarSobreSiMesmo(usuarioId, quemPede);
            GestaoDeUsuarios.exigirOutroAdministrador(usuarios, usuarioId, atual.perfil());
        }

        usuarios.definirAtivacao(usuarioId, ativo);
        if (!ativo) {
            tokens.revogarTodasDoUsuario(usuarioId, relogio.agora(), "usuário desativado");
        }
        auditoria.registrar(RegistroDeAuditoria.alteracao(
                ativo ? "USUARIO_REATIVADO" : "USUARIO_DESATIVADO", "usuario", usuarioId,
                Map.of("ativo", atual.ativo()), Map.of("ativo", ativo)));
        log.info("Usuário {} {}", usuarioId, ativo ? "reativado" : "desativado");
    }
}
