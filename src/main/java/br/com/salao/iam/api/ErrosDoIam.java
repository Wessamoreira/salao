package br.com.salao.iam.api;

import br.com.salao.shared.erro.CodigoDeErro;
import org.springframework.http.HttpStatus;

/** RT-IAM-001/002 — catálogo de erros do módulo. Ver docs/modulos/iam/regras.md. */
public enum ErrosDoIam implements CodigoDeErro {

    DADOS_INVALIDOS("ER-IAM-DADOS_INVALIDOS", HttpStatus.UNPROCESSABLE_ENTITY,
            "Dados do estabelecimento inválidos"),

    /**
     * Um único código para senha errada, usuário inexistente e usuário inativo.
     *
     * <p>Distinguir os casos entregaria de graça a resposta para "este e-mail existe no sistema?",
     * que é o primeiro passo de qualquer ataque de credenciais. A mensagem é deliberadamente a
     * mesma nos três casos.
     */
    CREDENCIAIS_INVALIDAS("ER-IAM-CREDENCIAIS_INVALIDAS", HttpStatus.UNAUTHORIZED,
            "E-mail ou senha incorretos"),

    ACESSO_BLOQUEADO("ER-IAM-ACESSO_BLOQUEADO", HttpStatus.TOO_MANY_REQUESTS,
            "Acesso temporariamente bloqueado"),

    /**
     * Um código só para refresh desconhecido, expirado, revogado e reusado.
     *
     * <p>Distinguir diria a quem está testando tokens o que aconteceu com cada um — inclusive que
     * um deles já existiu, o que confirma um vazamento para quem o obteve.
     */
    SESSAO_EXPIRADA("ER-IAM-SESSAO_EXPIRADA", HttpStatus.UNAUTHORIZED, "Sessão expirada"),

    /** Desafio inválido, código TOTP errado, reapresentado, ou recuperação já usada. */
    SEGUNDO_FATOR_INVALIDO("ER-IAM-SEGUNDO_FATOR_INVALIDO", HttpStatus.UNAUTHORIZED,
            "Código de verificação inválido"),

    MFA_NAO_INSCRITO("ER-IAM-MFA_NAO_INSCRITO", HttpStatus.UNPROCESSABLE_ENTITY,
            "Segundo fator não configurado"),

    EMAIL_JA_CADASTRADO("ER-IAM-EMAIL_JA_CADASTRADO", HttpStatus.CONFLICT,
            "E-mail já cadastrado"),

    /** Rebaixar ou desativar a si mesmo é quase sempre engano, e o estrago é imediato. */
    OPERACAO_SOBRE_SI_MESMO("ER-IAM-OPERACAO_SOBRE_SI_MESMO", HttpStatus.UNPROCESSABLE_ENTITY,
            "Não é possível fazer isso na própria conta"),

    /** Sem administrador ativo, a saída seria mexer no banco à mão. */
    ULTIMO_ADMINISTRADOR("ER-IAM-ULTIMO_ADMINISTRADOR", HttpStatus.UNPROCESSABLE_ENTITY,
            "O salão ficaria sem administrador"),

    SENHA_ATUAL_INCORRETA("ER-IAM-SENHA_ATUAL_INCORRETA", HttpStatus.UNPROCESSABLE_ENTITY,
            "Senha atual incorreta");

    private final String codigo;
    private final HttpStatus status;
    private final String titulo;

    ErrosDoIam(String codigo, HttpStatus status, String titulo) {
        this.codigo = codigo;
        this.status = status;
        this.titulo = titulo;
    }

    @Override
    public String codigo() {
        return codigo;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String titulo() {
        return titulo;
    }
}
