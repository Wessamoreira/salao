package br.com.salao.iam.internal.application;

import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.ErrosDoIam;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.api.Permissao;
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

/** RT-IAM-007 — alterar o perfil de um usuário. */
public class AlterarPerfilDoUsuarioUseCase {

    private static final Logger log =
            LoggerFactory.getLogger(AlterarPerfilDoUsuarioUseCase.class);

    private final UsuariosJdbc usuarios;
    private final CredenciaisJdbc credenciais;
    private final RefreshTokensJdbc tokens;
    private final Relogio relogio;
    private final AuditoriaApi auditoria;

    public AlterarPerfilDoUsuarioUseCase(UsuariosJdbc usuarios, CredenciaisJdbc credenciais,
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
    public void executar(UUID usuarioId, Perfil novoPerfil, UUID quemPede) {
        GestaoDeUsuarios.recusarSobreSiMesmo(usuarioId, quemPede);
        if (novoPerfil == Perfil.BOT) {
            throw new ErroDeDominio(ErrosDoIam.DADOS_INVALIDOS,
                    "BOT não é um perfil atribuível a pessoas");
        }

        var atual = credenciais.porId(usuarioId).orElseThrow(() ->
                new ErroDeDominio(ErrosDaInfra.NAO_ENCONTRADO, "Usuário não encontrado."));
        if (novoPerfil != Perfil.ADMIN) {
            GestaoDeUsuarios.exigirOutroAdministrador(usuarios, usuarioId, atual.perfil());
        }

        usuarios.alterarPerfil(usuarioId, novoPerfil);

        // Encerra as sessões: sem isso, o access token antigo — com o perfil antigo — continua
        // valendo por até 15 minutos. Numa promoção seria irrelevante; num rebaixamento, é
        // exatamente a janela que não se quer deixar aberta.
        tokens.revogarTodasDoUsuario(usuarioId, relogio.agora(), "perfil alterado");
        auditoria.registrar(RegistroDeAuditoria.alteracao("PERFIL_ALTERADO", "usuario", usuarioId,
                Map.of("perfil", atual.perfil().name()), Map.of("perfil", novoPerfil.name())));
        log.info("Perfil do usuário {} alterado de {} para {}", usuarioId, atual.perfil(),
                novoPerfil);
    }
}
