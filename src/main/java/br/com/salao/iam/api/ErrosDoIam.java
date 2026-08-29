package br.com.salao.iam.api;

import br.com.salao.shared.erro.CodigoDeErro;
import org.springframework.http.HttpStatus;

/** RT-IAM-001 — catálogo de erros do módulo. Ver docs/modulos/iam/regras.md. */
public enum ErrosDoIam implements CodigoDeErro {

    DADOS_INVALIDOS("ER-IAM-DADOS_INVALIDOS", HttpStatus.UNPROCESSABLE_ENTITY,
            "Dados do estabelecimento inválidos");

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
