package br.com.salao.financeiro.api;

import br.com.salao.shared.erro.CodigoDeErro;
import org.springframework.http.HttpStatus;

/** RT-FIN-001 — catálogo de erros do módulo. Ver docs/modulos/financeiro/regras.md. */
public enum ErrosDoFinanceiro implements CodigoDeErro {

    DADOS_INVALIDOS("ER-FIN-DADOS_INVALIDOS", HttpStatus.UNPROCESSABLE_ENTITY,
            "Dados do pagamento inválidos"),

    PAGAMENTO_NAO_ENCONTRADO("ER-FIN-PAGAMENTO_NAO_ENCONTRADO", HttpStatus.NOT_FOUND,
            "Pagamento não encontrado"),

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
