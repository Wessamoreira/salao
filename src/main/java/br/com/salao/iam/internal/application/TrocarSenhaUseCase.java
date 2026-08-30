package br.com.salao.iam.internal.application;

import br.com.salao.iam.api.ErrosDoIam;
import br.com.salao.iam.internal.infra.CredenciaisJdbc;
import br.com.salao.iam.internal.infra.RefreshTokensJdbc;
import br.com.salao.iam.internal.infra.UsuariosJdbc;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.erro.ErrosDaInfra;
import br.com.salao.shared.tempo.Relogio;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * RT-IAM-007 — trocar a própria senha.
 *
 * <p>Sem {@code @PreAuthorize}: qualquer usuário autenticado troca a <em>própria</em> senha, e o
 * id vem do token — não do corpo da requisição. Aceitá-lo do corpo transformaria isto em "trocar a
 * senha de qualquer um".
 */
public class TrocarSenhaUseCase {

    private static final Logger log = LoggerFactory.getLogger(TrocarSenhaUseCase.class);

    private static final int TAMANHO_MINIMO = 12;

    private final UsuariosJdbc usuarios;
    private final CredenciaisJdbc credenciais;
    private final RefreshTokensJdbc tokens;
    private final PasswordEncoder codificador;
    private final Relogio relogio;

    public TrocarSenhaUseCase(UsuariosJdbc usuarios, CredenciaisJdbc credenciais,
                              RefreshTokensJdbc tokens, PasswordEncoder codificador,
                              Relogio relogio) {
        this.usuarios = usuarios;
        this.credenciais = credenciais;
        this.tokens = tokens;
        this.codificador = codificador;
        this.relogio = relogio;
    }

    public void executar(UUID usuarioId, String senhaAtual, String senhaNova) {
        if (senhaNova == null || senhaNova.length() < TAMANHO_MINIMO) {
            throw new ErroDeDominio(ErrosDoIam.DADOS_INVALIDOS,
                    "a nova senha precisa de ao menos " + TAMANHO_MINIMO + " caracteres");
        }
        var usuario = credenciais.porId(usuarioId).orElseThrow(() ->
                new ErroDeDominio(ErrosDaInfra.NAO_ENCONTRADO, "Usuário não encontrado."));

        // Exigir a senha atual protege contra quem senta no computador do salão com a sessão
        // aberta — que, num balcão compartilhado, é o cenário mais provável de todos.
        if (!codificador.matches(senhaAtual == null ? "" : senhaAtual, usuario.senhaHash())) {
            throw new ErroDeDominio(ErrosDoIam.SENHA_ATUAL_INCORRETA,
                    "A senha atual não confere.");
        }

        usuarios.trocarSenha(usuarioId, codificador.encode(senhaNova));

        // Todas as sessões caem, inclusive a de quem trocou. Trocar senha é o que se faz ao
        // suspeitar que alguém tem acesso — manter as sessões abertas manteria justamente o
        // acesso que se está tentando cortar. O preço é entrar de novo, e é um preço baixo.
        int revogados = tokens.revogarTodasDoUsuario(usuarioId, relogio.agora(), "senha alterada");
        log.info("Senha do usuário {} alterada; {} sessão(ões) encerrada(s)",
                usuarioId, revogados);
    }
}
