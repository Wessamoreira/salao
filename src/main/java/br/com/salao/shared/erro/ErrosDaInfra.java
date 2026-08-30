package br.com.salao.shared.erro;

import org.springframework.http.HttpStatus;

/**
 * RT-INF-003 — erros transversais. Erro de negócio mora no enum do módulo dono da regra.
 */
public enum ErrosDaInfra implements CodigoDeErro {

    /**
     * Recurso inexistente <strong>ou</strong> fora do escopo do usuário.
     *
     * <p>Deliberadamente indistinguíveis: responder 403 para recurso de outro tenant confirmaria
     * que ele existe, o que é vazamento de informação por si só.
     */
    NAO_ENCONTRADO("ER-INF-NAO_ENCONTRADO", HttpStatus.NOT_FOUND, "Não encontrado"),

    VERSAO_DESATUALIZADA("ER-INF-VERSAO_DESATUALIZADA", HttpStatus.CONFLICT,
            "Registro alterado por outra pessoa"),

    IDEMPOTENCIA_CONFLITO("ER-INF-IDEMPOTENCIA_CONFLITO", HttpStatus.UNPROCESSABLE_ENTITY,
            "Chave de idempotência reutilizada com outro conteúdo"),

    DADOS_INVALIDOS("ER-INF-DADOS_INVALIDOS", HttpStatus.BAD_REQUEST, "Dados inválidos"),

    LIMITE_DE_REQUISICOES("ER-INF-LIMITE_DE_REQUISICOES", HttpStatus.TOO_MANY_REQUESTS,
            "Muitas requisições"),

    /**
     * O recurso está sendo alterado por outra requisição e a espera estourou o
     * {@code lock_timeout} (RT-INF-012). É estado transitório: tentar de novo costuma resolver,
     * e é por isso que não é 500 — 500 diz "algo quebrou", e nada quebrou.
     */
    OPERACAO_EM_ANDAMENTO("ER-INF-OPERACAO_EM_ANDAMENTO", HttpStatus.CONFLICT,
            "Operação em andamento"),

    METODO_NAO_PERMITIDO("ER-INF-METODO_NAO_PERMITIDO", HttpStatus.METHOD_NOT_ALLOWED,
            "Método não permitido"),

    ERRO_INTERNO("ER-INF-ERRO_INTERNO", HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno");

    private final String codigo;
    private final HttpStatus status;
    private final String titulo;

    ErrosDaInfra(String codigo, HttpStatus status, String titulo) {
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
