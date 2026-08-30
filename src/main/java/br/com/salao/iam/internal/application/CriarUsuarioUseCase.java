package br.com.salao.iam.internal.application;

import br.com.salao.iam.api.ErrosDoIam;
import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.internal.domain.Emails;
import br.com.salao.iam.internal.infra.UsuariosJdbc;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.TenantContext;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;

/** RT-IAM-007 — criar usuário. */
public class CriarUsuarioUseCase {

    private static final Logger log = LoggerFactory.getLogger(CriarUsuarioUseCase.class);

    private static final int TAMANHO_MINIMO_DA_SENHA = 12;

    private final UsuariosJdbc usuarios;
    private final PasswordEncoder codificador;

    public CriarUsuarioUseCase(UsuariosJdbc usuarios, PasswordEncoder codificador) {
        this.usuarios = usuarios;
        this.codificador = codificador;
    }

    /**
     * A autorização vive <strong>aqui</strong>, no caso de uso — não no controller. É o que faz a
     * regra valer também para o bot da Fase 4, que não passa por controller nenhum.
     */
    @PreAuthorize("hasAuthority('" + Permissao.USUARIO_GERENCIAR + "')")
    public UUID executar(String nome, String email, String senha, Perfil perfil) {
        if (nome == null || nome.isBlank() || email == null || email.isBlank()) {
            throw new ErroDeDominio(ErrosDoIam.DADOS_INVALIDOS,
                    "nome e e-mail são obrigatórios");
        }
        if (senha == null || senha.length() < TAMANHO_MINIMO_DA_SENHA) {
            // Comprimento em vez de composição: exigir símbolo empurra a pessoa para
            // "Salao@2026", que é pior do que uma frase longa.
            throw new ErroDeDominio(ErrosDoIam.DADOS_INVALIDOS,
                    "a senha precisa de ao menos " + TAMANHO_MINIMO_DA_SENHA + " caracteres");
        }
        if (perfil == Perfil.BOT) {
            // O bot não é uma conta: ele age em nome de um usuário e herda as permissões dele.
            // Criar um usuário BOT seria criar exatamente o confused deputy que o projeto evita.
            throw new ErroDeDominio(ErrosDoIam.DADOS_INVALIDOS,
                    "BOT não é um perfil atribuível a pessoas");
        }

        try {
            UUID id = usuarios.criar(TenantContext.obrigatorio(), nome.trim(), email.trim(),
                    Emails.normalizar(email), codificador.encode(senha), perfil);
            log.info("Usuário criado: {} com perfil {}", id, perfil);
            return id;
        } catch (DuplicateKeyException e) {
            // O índice de e-mail é GLOBAL (ver V7): a colisão pode ser com outro estabelecimento.
            // A mensagem é a mesma nos dois casos — dizer "existe em outro salão" contaria a um
            // administrador algo sobre um tenant que não é o dele.
            throw new ErroDeDominio(ErrosDoIam.EMAIL_JA_CADASTRADO,
                    "Este e-mail já está em uso.");
        }
    }
}
