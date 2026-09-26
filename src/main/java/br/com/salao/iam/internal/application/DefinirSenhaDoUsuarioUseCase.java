package br.com.salao.iam.internal.application;

import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.ErrosDoIam;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.iam.internal.infra.RefreshTokensJdbc;
import br.com.salao.iam.internal.infra.UsuariosJdbc;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.erro.ErrosDaInfra;
import br.com.salao.shared.tempo.Relogio;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-IAM-009 / RN-IAM-025 — o administrador define uma senha nova para outra pessoa.
 *
 * <h2>Fecha a metade que faltava</h2>
 *
 * <p>Perder o celular do segundo fator já tinha conserto ({@code ResetarSegundoFatorUseCase}).
 * Esquecer a senha — que é muito mais comum — não tinha: quem esquecia ficava fora do sistema, e
 * a saída era mexer no banco.
 *
 * <h2>Sobre a própria conta, nunca</h2>
 *
 * <p>É o que mantém de pé a exigência da senha atual em {@link TrocarSenhaUseCase}. Sem esta
 * recusa, quem sentasse no computador do balcão com a sessão do dono aberta trocaria a senha do
 * dono <strong>sem saber a senha do dono</strong> — exatamente o cenário para o qual a senha atual
 * é pedida.
 *
 * <p>Operação perigosa, e o desenho reconhece, como em {@code resetar-mfa}: exige
 * {@code usuario:manage}, encerra as sessões do alvo, audita e registra em log de aviso.
 * Confirmar a identidade de quem pediu é de quem administra — nenhum sistema resolve isso sozinho.
 *
 * <p>A auditoria registra que a senha mudou, nunca o que ela é: trilha com senha dentro é
 * vazamento com data e hora.
 */
public class DefinirSenhaDoUsuarioUseCase {

    private static final Logger log =
            LoggerFactory.getLogger(DefinirSenhaDoUsuarioUseCase.class);

    /** O mesmo mínimo de {@code CriarUsuarioUseCase}: comprimento, não composição. */
    private static final int TAMANHO_MINIMO = 12;

    private final UsuariosJdbc usuarios;
    private final RefreshTokensJdbc tokens;
    private final PasswordEncoder codificador;
    private final Relogio relogio;
    private final AuditoriaApi auditoria;

    public DefinirSenhaDoUsuarioUseCase(UsuariosJdbc usuarios, RefreshTokensJdbc tokens,
                                        PasswordEncoder codificador, Relogio relogio,
                                        AuditoriaApi auditoria) {
        this.usuarios = usuarios;
        this.tokens = tokens;
        this.codificador = codificador;
        this.relogio = relogio;
        this.auditoria = auditoria;
    }

    @PreAuthorize("hasAuthority('" + Permissao.USUARIO_GERENCIAR + "')")
    @Transactional
    public void executar(UUID usuarioId, String senhaNova, UUID quemPede) {
        GestaoDeUsuarios.recusarSobreSiMesmo(usuarioId, quemPede);
        if (senhaNova == null || senhaNova.length() < TAMANHO_MINIMO) {
            throw new ErroDeDominio(ErrosDoIam.DADOS_INVALIDOS,
                    "a senha precisa de ao menos " + TAMANHO_MINIMO + " caracteres");
        }
        usuarios.porId(usuarioId).orElseThrow(() ->
                new ErroDeDominio(ErrosDaInfra.NAO_ENCONTRADO, "Usuário não encontrado."));

        // `trocarSenha` também zera falhas e bloqueio: quem chega aqui está destravando alguém,
        // e deixar o bloqueio por tentativas de pé faria a senha nova não funcionar.
        usuarios.trocarSenha(usuarioId, codificador.encode(senhaNova));
        int revogados =
                tokens.revogarTodasDoUsuario(usuarioId, relogio.agora(), "senha definida pelo admin");

        auditoria.registrar(RegistroDeAuditoria.alteracao("SENHA_DEFINIDA_PELO_ADMIN", "usuario",
                usuarioId, Map.of(), Map.of("sessoesEncerradas", revogados)));
        log.warn("Senha do usuário {} DEFINIDA pelo administrador {}; {} sessão(ões) encerrada(s). "
                + "Se o pedido não partiu do próprio usuário, isto é um incidente.",
                usuarioId, quemPede, revogados);
    }
}
