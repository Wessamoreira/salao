package br.com.salao.iam.internal.application;

import br.com.salao.iam.api.ErrosDoIam;
import br.com.salao.iam.api.SessaoIniciada;
import br.com.salao.iam.internal.domain.CredencialDeAcesso;
import br.com.salao.iam.internal.domain.Emails;
import br.com.salao.iam.internal.domain.PoliticaDeBloqueio;
import br.com.salao.iam.internal.infra.CredenciaisJdbc;
import br.com.salao.iam.internal.domain.SegredoOpaco;
import br.com.salao.iam.internal.infra.EmissorDeTokenJwt;
import br.com.salao.iam.internal.infra.RefreshTokensJdbc;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * RT-IAM-002 — autenticar por e-mail e senha.
 *
 * <h2>O login é o único fluxo que começa sem tenant</h2>
 *
 * <p>A pessoa digita e-mail e senha; o estabelecimento é <em>consequência</em> do e-mail, não
 * entrada. Por isso a busca da credencial é o único alcance cross-tenant do fluxo — uma consulta
 * estreita pela conexão de plataforma. A partir do instante em que o tenant é conhecido, tudo
 * corre dentro do escopo dele, sob RLS.
 *
 * <h2>Três decisões contra enumeração de usuários</h2>
 *
 * <p><strong>Um código de erro só.</strong> Senha errada, e-mail inexistente e usuário inativo
 * devolvem {@code ER-IAM-CREDENCIAIS_INVALIDAS}. Distinguir entregaria de graça a resposta para
 * "este e-mail existe aqui?", que é o primeiro passo de qualquer ataque de credenciais.
 *
 * <p><strong>Custo de tempo igual.</strong> Quando o e-mail não existe, a senha é conferida contra
 * um hash descartável mesmo assim. Sem isso, a resposta para e-mail inexistente voltaria em
 * milissegundos e a de senha errada em centenas — e essa diferença é mensurável de fora.
 *
 * <p><strong>Bloqueio progressivo</strong> (RN-IAM-005), contado no banco e não em memória: em
 * memória, duas instâncias contariam metade cada e subir uma terceira afrouxaria a proteção.
 */
public class AutenticarUseCase {

    private static final Logger log = LoggerFactory.getLogger(AutenticarUseCase.class);

    private final CredenciaisJdbc credenciais;
    private final PasswordEncoder codificador;
    private final EmissorDeTokenJwt emissor;
    private final RefreshTokensJdbc refreshTokens;
    private final Relogio relogio;
    private final Duration validadeDoRefresh;

    /**
     * Hash descartável, gerado uma vez na subida, para gastar o mesmo tempo quando o e-mail não
     * existe. Precisa ser um hash de verdade: comparar contra uma string qualquer falharia rápido
     * demais e reintroduziria a diferença de tempo.
     */
    private final String hashDeReferencia;

    public AutenticarUseCase(CredenciaisJdbc credenciais, PasswordEncoder codificador,
                             EmissorDeTokenJwt emissor, RefreshTokensJdbc refreshTokens,
                             Relogio relogio, Duration validadeDoRefresh) {
        this.credenciais = credenciais;
        this.codificador = codificador;
        this.emissor = emissor;
        this.refreshTokens = refreshTokens;
        this.relogio = relogio;
        this.validadeDoRefresh = validadeDoRefresh;
        this.hashDeReferencia = codificador.encode(UUID.randomUUID().toString());
    }

    public SessaoIniciada executar(AutenticarCommand comando) {
        String email = Emails.normalizar(comando.email());
        if (email == null || email.isBlank() || comando.senha() == null) {
            throw credenciaisInvalidas();
        }

        var encontrada = credenciais.porEmail(email);
        if (encontrada.isEmpty()) {
            codificador.matches(comando.senha(), hashDeReferencia);
            log.info("Login recusado: e-mail não cadastrado");
            throw credenciaisInvalidas();
        }

        CredencialDeAcesso credencial = encontrada.get();
        return TenantContext.obter(credencial.estabelecimentoId(),
                () -> concluir(credencial, comando.senha()));
    }

    private SessaoIniciada concluir(CredencialDeAcesso credencial, String senha) {
        Instant agora = relogio.agora();

        if (PoliticaDeBloqueio.estaBloqueado(credencial.bloqueadoAte(), agora)) {
            // Não confere a senha: além de inútil, conferir permitiria descobrir a senha correta
            // durante o bloqueio, observando a diferença de tempo.
            log.warn("Login recusado: usuário {} bloqueado até {}",
                    credencial.usuarioId(), credencial.bloqueadoAte());
            throw new ErroDeDominio(ErrosDoIam.ACESSO_BLOQUEADO,
                    "Muitas tentativas. Tente novamente em alguns minutos.");
        }

        if (!codificador.matches(senha, credencial.senhaHash())) {
            registrarFalha(credencial, agora);
            throw credenciaisInvalidas();
        }

        if (!credencial.ativo()) {
            // Senha correta, mas o acesso foi desativado. Não conta como falha — o usuário não
            // errou nada, e contar aqui bloquearia uma conta que já está inacessível.
            log.info("Login recusado: usuário {} inativo", credencial.usuarioId());
            throw credenciaisInvalidas();
        }

        credenciais.registrarSucesso(credencial.usuarioId());

        // Cada login abre uma FAMÍLIA nova de refresh. Entrar de novo não derruba a sessão do
        // celular: são cadeias independentes, e o reuso detectado numa não afeta a outra.
        String segredo = SegredoOpaco.gerar();
        Instant expiraEm = agora.plus(validadeDoRefresh);
        refreshTokens.emitirNovaFamilia(credencial.estabelecimentoId(), credencial.usuarioId(),
                UUID.randomUUID(), SegredoOpaco.hashDe(segredo), expiraEm, null, null);

        log.info("Login concluído para o usuário {}", credencial.usuarioId());
        var acesso = emissor.emitir(credencial.usuarioId(), credencial.estabelecimentoId(),
                credencial.perfil());
        return new SessaoIniciada(acesso, segredo, expiraEm);
    }

    private void registrarFalha(CredencialDeAcesso credencial, Instant agora) {
        int falhas = credencial.falhasConsecutivas() + 1;
        Instant bloqueio = PoliticaDeBloqueio.bloqueioApos(falhas, agora);
        credenciais.registrarFalha(credencial.usuarioId(), bloqueio);
        log.info("Login recusado: senha incorreta para o usuário {} ({} falhas consecutivas)",
                credencial.usuarioId(), falhas);
    }

    private ErroDeDominio credenciaisInvalidas() {
        return new ErroDeDominio(ErrosDoIam.CREDENCIAIS_INVALIDAS, "E-mail ou senha incorretos.");
    }
}
