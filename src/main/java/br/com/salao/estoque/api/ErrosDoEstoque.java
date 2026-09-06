package br.com.salao.estoque.api;

import br.com.salao.shared.erro.CodigoDeErro;
import org.springframework.http.HttpStatus;

/** RT-EST-001 — catálogo de erros do módulo. Ver docs/modulos/estoque/regras.md. */
public enum ErrosDoEstoque implements CodigoDeErro {

    DADOS_INVALIDOS("ER-EST-DADOS_INVALIDOS", HttpStatus.UNPROCESSABLE_ENTITY,
            "Dados do produto inválidos"),

    NOME_JA_CADASTRADO("ER-EST-NOME_JA_CADASTRADO", HttpStatus.CONFLICT,
            "Já existe um produto com este nome"),

    PRODUTO_NAO_ENCONTRADO("ER-EST-PRODUTO_NAO_ENCONTRADO", HttpStatus.NOT_FOUND,
            "Produto não encontrado"),

    /**
     * Tentou precificar um produto que só se consome.
     *
     * <p>Código próprio porque a correção não é "digite outro valor": é mudar a finalidade do
     * produto, ou não precificá-lo. Um preço em produto de consumo interno acabaria lido por
     * alguma tela de venda, e a tinta apareceria no balcão.
     */
    CONSUMO_INTERNO_NAO_TEM_PRECO("ER-EST-CONSUMO_INTERNO_NAO_TEM_PRECO",
            HttpStatus.UNPROCESSABLE_ENTITY, "Produto de consumo interno não tem preço de venda"),

    /**
     * RT-EST-002 — entrada de lote com quantidade que não entra.
     *
     * <p>Código próprio porque a correção é do número digitado, e não do produto: zero não move
     * nada, e negativo é saída — que é outra rotina, com motivo obrigatório (RT-EST-005).
     */
    QUANTIDADE_INVALIDA("ER-EST-QUANTIDADE_INVALIDA", HttpStatus.UNPROCESSABLE_ENTITY,
            "A quantidade de entrada precisa ser maior que zero"),

    PRODUTO_INATIVO("ER-EST-PRODUTO_INATIVO", HttpStatus.UNPROCESSABLE_ENTITY,
            "Produto desativado não recebe entrada"),

    /**
     * Alguém salvou por cima de uma tela velha.
     *
     * <p>O bloqueio otimista existe aqui porque preço e finalidade são editados por pessoas
     * diferentes — a recepção cadastra, o dono precifica —, e a última gravação venceria em
     * silêncio.
     */
    CONFLITO_DE_VERSAO("ER-EST-CONFLITO_DE_VERSAO", HttpStatus.CONFLICT,
            "Este produto foi alterado por outra pessoa");

    private final String codigo;
    private final HttpStatus status;
    private final String titulo;

    ErrosDoEstoque(String codigo, HttpStatus status, String titulo) {
        this.codigo = codigo;
        this.status = status;
        this.titulo = titulo;
    }

    @Override public String codigo() { return codigo; }
    @Override public HttpStatus status() { return status; }
    @Override public String titulo() { return titulo; }
}
