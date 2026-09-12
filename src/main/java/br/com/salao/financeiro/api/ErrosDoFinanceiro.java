package br.com.salao.financeiro.api;

import br.com.salao.shared.erro.CodigoDeErro;
import org.springframework.http.HttpStatus;

/** RT-FIN-001 — catálogo de erros do módulo. Ver docs/modulos/financeiro/regras.md. */
public enum ErrosDoFinanceiro implements CodigoDeErro {

    DADOS_INVALIDOS("ER-FIN-DADOS_INVALIDOS", HttpStatus.UNPROCESSABLE_ENTITY,
            "Dados do pagamento inválidos"),

    PAGAMENTO_NAO_ENCONTRADO("ER-FIN-PAGAMENTO_NAO_ENCONTRADO", HttpStatus.NOT_FOUND,
            "Pagamento não encontrado"),

    REGRA_NAO_ENCONTRADA("ER-FIN-REGRA_NAO_ENCONTRADA", HttpStatus.NOT_FOUND,
            "Regra de comissão não encontrada"),

    VALE_NAO_ENCONTRADO("ER-FIN-VALE_NAO_ENCONTRADO", HttpStatus.NOT_FOUND,
            "Vale não encontrado"),

    /**
     * Período já fechado, ou anterior a um já fechado.
     *
     * <p>Código próprio e {@code 409} porque nada está errado no pedido: ele chegou tarde. A
     * correção é escolher outro período, não corrigir um campo — e um {@code 422} mandaria quem
     * chamou procurar o dado inválido que não existe.
     */
    PERIODO_JA_FECHADO("ER-FIN-PERIODO_JA_FECHADO", HttpStatus.CONFLICT,
            "Período já fechado"),

    PREVIA_DESATUALIZADA("ER-FIN-PREVIA_DESATUALIZADA", HttpStatus.CONFLICT,
            "Prévia do fechamento desatualizada"),

    SEM_POSICOES_PARA_FECHAR("ER-FIN-SEM_POSICOES_PARA_FECHAR",
            HttpStatus.UNPROCESSABLE_ENTITY, "Não há posições para fechar"),

    /**
     * Parcelamento informado para uma forma que não parcela.
     *
     * <p>Código próprio porque o número não está errado — o que está errado é a combinação, e a
     * correção é trocar a forma ou tirar as parcelas.
     */
    SO_CREDITO_PARCELA("ER-FIN-SO_CREDITO_PARCELA", HttpStatus.UNPROCESSABLE_ENTITY,
            "Só crédito é parcelado");

    private final String codigo;
    private final HttpStatus status;
    private final String titulo;

    ErrosDoFinanceiro(String codigo, HttpStatus status, String titulo) {
        this.codigo = codigo;
        this.status = status;
        this.titulo = titulo;
    }

    @Override public String codigo() { return codigo; }
    @Override public HttpStatus status() { return status; }
    @Override public String titulo() { return titulo; }
}
