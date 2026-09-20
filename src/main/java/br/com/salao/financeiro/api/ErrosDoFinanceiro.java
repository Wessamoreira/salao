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

    FECHAMENTO_NAO_ENCONTRADO("ER-FIN-FECHAMENTO_NAO_ENCONTRADO", HttpStatus.NOT_FOUND,
            "Fechamento não encontrado"),

    LANCAMENTO_NAO_ENCONTRADO("ER-FIN-LANCAMENTO_NAO_ENCONTRADO", HttpStatus.NOT_FOUND,
            "Lançamento não encontrado"),

    /**
     * Tentativa de estornar um estorno.
     *
     * <p>Código próprio porque a intenção de quem pediu é ambígua, e adivinhar sairia caro:
     * anular uma anulação é reaplicar o valor original, e reaplicar dinheiro nunca deve
     * acontecer por engano de clique. Quem quer o valor de volta lança um {@code AJUSTE} com
     * motivo — um ato deliberado, com rastro próprio.
     */
    ESTORNO_DE_ESTORNO("ER-FIN-ESTORNO_DE_ESTORNO", HttpStatus.UNPROCESSABLE_ENTITY,
            "Um estorno não se estorna"),

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
            "Só crédito é parcelado"),

    /** RT-FIN-009 — o arquivo do extrato não dá para ler: vazio, planilha, sem colunas, grande. */
    ARQUIVO_INVALIDO("ER-FIN-ARQUIVO_INVALIDO", HttpStatus.UNPROCESSABLE_ENTITY,
            "Arquivo do extrato inválido"),

    /**
     * RT-FIN-009 — falta dizer qual coluna é o Stone ID, a data ou o valor bruto.
     *
     * <p>Código próprio porque o arquivo pode estar certo: o que falta é a escolha na prévia.
     */
    MAPEAMENTO_INCOMPLETO("ER-FIN-MAPEAMENTO_INCOMPLETO", HttpStatus.UNPROCESSABLE_ENTITY,
            "Faltam colunas no mapeamento do extrato"),

    /** RT-FIN-010 — período invertido ou longo demais, ou cursor que não veio da tela. */
    FILTRO_INVALIDO("ER-FIN-FILTRO_INVALIDO", HttpStatus.UNPROCESSABLE_ENTITY,
            "Filtro da conciliação inválido"),

    /** RT-FIN-011 — a linha da conferência, ou a resolução, não existe ou é de outro salão. */
    RESOLUCAO_NAO_ENCONTRADA("ER-FIN-RESOLUCAO_NAO_ENCONTRADA", HttpStatus.NOT_FOUND,
            "Linha da conferência não encontrada"),

    /**
     * RT-FIN-011 — a ação não cabe no alvo: corrigir um par que não tem o que corrigir, lançar uma
     * venda que já casou, trocar a forma de um pagamento que não é de cartão.
     *
     * <p>Código próprio porque nenhum campo do pedido está errado — o que não cabe é a ação, e a
     * correção é escolher outra. Um {@code DADOS_INVALIDOS} mandaria quem chamou procurar o campo.
     */
    RESOLUCAO_NAO_APLICAVEL("ER-FIN-RESOLUCAO_NAO_APLICAVEL", HttpStatus.UNPROCESSABLE_ENTITY,
            "Esta ação não cabe nesta linha da conferência"),

    /**
     * RT-FIN-011 — o alvo já tinha resolução valendo.
     *
     * <p>{@code 409} pela mesma razão de {@link #PERIODO_JA_FECHADO}: o pedido chegou tarde. São dois
     * cliques no mesmo botão, ou duas abas abertas na mesma conferência.
     */
    JA_RESOLVIDA("ER-FIN-JA_RESOLVIDA", HttpStatus.CONFLICT,
            "Esta linha já foi resolvida");

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
