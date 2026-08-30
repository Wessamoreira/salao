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
            "Acesso temporariamente bloqueado");

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
