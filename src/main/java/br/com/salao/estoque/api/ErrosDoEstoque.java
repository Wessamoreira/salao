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
     * RT-EST-004 — não há unidades suficientes para a saída.
     *
     * <p>A mensagem diz quanto há, não só que faltou: quem está no balcão precisa saber se vende
     * o que resta ou se troca o produto, e "saldo insuficiente" sozinho não responde nenhuma das
     * duas.
     */
    /**
     * Código de barras já usado por outro produto do salão (RT-EST-009).
     *
     * <p>{@code 409} e não {@code 422}: o código está certo, o conflito é com o cadastro. A
     * mensagem nomeia o produto que já o tem, porque a ação de quem bipou é ir ver aquele
     * produto — quase sempre é o mesmo item cadastrado duas vezes.
     */
    CODIGO_DE_BARRAS_EM_USO("ER-EST-CODIGO_DE_BARRAS_EM_USO", HttpStatus.CONFLICT,
            "Este código de barras já é de outro produto"),

    /** O leitor bipou algo que não está cadastrado. */
    CODIGO_DE_BARRAS_DESCONHECIDO("ER-EST-CODIGO_DE_BARRAS_DESCONHECIDO", HttpStatus.NOT_FOUND,
            "Nenhum produto com este código de barras"),

    SERVICO_NAO_ENCONTRADO("ER-EST-SERVICO_NAO_ENCONTRADO", HttpStatus.NOT_FOUND,
            "Serviço não encontrado"),

    /**
     * Produto de venda posto numa ficha técnica (RT-EST-007).
     *
     * <p>Código próprio porque a correção não é no número: é no cadastro do produto. Um shampoo
     * de prateleira usado no serviço precisa ter finalidade {@code AMBOS} — e essa decisão tem
     * consequência de preço, então ela é do cadastro e não de um aceite silencioso aqui.
     */
    PRODUTO_NAO_E_CONSUMIVEL("ER-EST-PRODUTO_NAO_E_CONSUMIVEL", HttpStatus.UNPROCESSABLE_ENTITY,
            "Só produto de consumo interno entra na ficha técnica"),

    INVENTARIO_NAO_ENCONTRADO("ER-EST-INVENTARIO_NAO_ENCONTRADO", HttpStatus.NOT_FOUND,
            "Inventário não encontrado"),

    /**
     * Já existe um inventário aberto neste salão (RT-EST-006).
     *
     * <p>{@code 409} e não {@code 422}: nada está errado no pedido, ele chegou na hora errada.
     * Dois inventários abertos contariam o mesmo lote com esperados diferentes, e aplicar os dois
     * somaria duas correções para uma divergência — o estoque ficaria pior que antes da contagem.
     */
    INVENTARIO_JA_ABERTO("ER-EST-INVENTARIO_JA_ABERTO", HttpStatus.CONFLICT,
            "Já existe um inventário aberto"),

    /** Contar ou aplicar um inventário que já foi encerrado. */
    INVENTARIO_ENCERRADO("ER-EST-INVENTARIO_ENCERRADO", HttpStatus.CONFLICT,
            "Este inventário já foi encerrado"),

    LOTE_NAO_ENCONTRADO("ER-EST-LOTE_NAO_ENCONTRADO", HttpStatus.NOT_FOUND,
            "Lote não encontrado"),

    /**
     * Perda, ajuste ou devolução sem explicação (RT-EST-005).
     *
     * <p>Código próprio porque a recusa não é sobre número: é sobre a falta da única informação
     * que faz o movimento ter valor depois. "Faltam 3 unidades" sem motivo não se investiga.
     */
    MOTIVO_OBRIGATORIO("ER-EST-MOTIVO_OBRIGATORIO", HttpStatus.UNPROCESSABLE_ENTITY,
            "Perda, ajuste e devolução exigem motivo"),

    SALDO_INSUFICIENTE("ER-EST-SALDO_INSUFICIENTE", HttpStatus.UNPROCESSABLE_ENTITY,
            "Não há saldo suficiente em estoque"),

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
