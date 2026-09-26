package br.com.salao.iam.internal.application;

import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.ErrosDoIam;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.iam.internal.domain.Emails;
import br.com.salao.iam.internal.infra.RefreshTokensJdbc;
import br.com.salao.iam.internal.infra.UsuariosJdbc;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.erro.ErrosDaInfra;
import br.com.salao.shared.tempo.Relogio;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-IAM-009 / RN-IAM-024 — corrigir nome e e-mail de uma conta.
 *
 * <h2>Por que precisou existir</h2>
 *
 * <p>O índice de e-mail é global (V7). Um endereço digitado errado no cadastro deixava a pessoa
 * sem conseguir entrar <strong>e</strong> ocupava o endereço certo para sempre — a única saída era
 * {@code update} no banco. É um campo digitado uma vez, por outra pessoa: errar é o caso normal.
 *
 * <h2>Só nome e e-mail</h2>
 *
 * <p>Perfil, ativação e senha têm caminho próprio, cada um com a sua trava — último administrador,
 * operação sobre si mesmo, revogação de sessão. Aceitá-los aqui seria abrir uma porta lateral para
 * regras que existem justamente para não ter porta lateral.
 */
public class EditarUsuarioUseCase {

    private static final Logger log = LoggerFactory.getLogger(EditarUsuarioUseCase.class);

    private final UsuariosJdbc usuarios;
    private final RefreshTokensJdbc tokens;
    private final Relogio relogio;
    private final AuditoriaApi auditoria;

    public EditarUsuarioUseCase(UsuariosJdbc usuarios, RefreshTokensJdbc tokens, Relogio relogio,
                                AuditoriaApi auditoria) {
        this.usuarios = usuarios;
        this.tokens = tokens;
        this.relogio = relogio;
        this.auditoria = auditoria;
    }

    @PreAuthorize("hasAuthority('" + Permissao.USUARIO_GERENCIAR + "')")
    @Transactional
    public void executar(UUID usuarioId, String nome, String email) {
        if (nome == null || nome.isBlank() || email == null || email.isBlank()) {
            throw new ErroDeDominio(ErrosDoIam.DADOS_INVALIDOS, "nome e e-mail são obrigatórios");
        }
        var atual = usuarios.porId(usuarioId).orElseThrow(() ->
                new ErroDeDominio(ErrosDaInfra.NAO_ENCONTRADO, "Usuário não encontrado."));

        String novoNome = nome.trim();
        String novoEmail = email.trim();
        // Comparação pelo e-mail normalizado: trocar "Ana@Salao.com" por "ana@salao.com" é a mesma
        // credencial escrita de outro jeito, e derrubar a sessão de alguém por causa de maiúscula
        // seria castigo por conserto.
        boolean trocouEmail =
                !Emails.normalizar(novoEmail).equals(Emails.normalizar(atual.email()));

        try {
            usuarios.editar(usuarioId, novoNome, novoEmail, Emails.normalizar(novoEmail));
        } catch (DuplicateKeyException e) {
            // Mesma frase de CriarUsuarioUseCase: o índice é global, e dizer "existe em outro
            // salão" contaria a este administrador algo sobre um tenant que não é o dele.
            throw new ErroDeDominio(ErrosDoIam.EMAIL_JA_CADASTRADO, "Este e-mail já está em uso.");
        }

        if (trocouEmail) {
            // O e-mail é o identificador de entrada: a sessão aberta foi obtida com uma credencial
            // que não existe mais (RN-IAM-024). No nome não se mexe — nome não é credencial.
            tokens.revogarTodasDoUsuario(usuarioId, relogio.agora(), "e-mail alterado");
        }
        auditoria.registrar(RegistroDeAuditoria.alteracao("USUARIO_EDITADO", "usuario", usuarioId,
                Map.of("nome", atual.nome(), "email", atual.email()),
                Map.of("nome", novoNome, "email", novoEmail)));
        log.info("Usuário {} editado; e-mail {}", usuarioId, trocouEmail ? "trocado" : "mantido");
    }
}
