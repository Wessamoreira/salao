package br.com.salao.iam.api;

/**
 * RT-IAM-006 — catálogo de permissões, no formato {@code recurso:acao:escopo}.
 *
 * <p>A autorização olha <strong>permissão</strong>, nunca perfil. Um {@code if (perfil == ADMIN)}
 * espalhado pelo código torna impossível atender "a recepção daqui também pode dar desconto até
 * 5%" sem alterar e reimplantar o sistema — e esse pedido chega de todo salão, mais cedo ou mais
 * tarde.
 *
 * <p>Constantes, e não enum: são {@code String} porque viajam no JSON de
 * {@code /me/capabilities} e no SpEL do {@code @PreAuthorize}. Um enum obrigaria a converter nos
 * dois lugares e ainda assim seria comparado como texto no final.
 */
public final class Permissao {

    private Permissao() {
    }

    /** {@code :own} é filtro de query, não de tela — ver RN-IAM-013. */
    public static final String AGENDA_LER_PROPRIA = "agenda:read:own";
    public static final String AGENDA_LER_TODAS = "agenda:read:all";
    public static final String AGENDA_ESCREVER_PROPRIA = "agenda:write:own";
    public static final String AGENDA_ESCREVER_TODAS = "agenda:write:all";

    public static final String COMANDA_ABRIR = "comanda:open";
    public static final String COMANDA_FECHAR = "comanda:close";
    public static final String COMANDA_DESCONTO = "comanda:discount";
    public static final String COMANDA_REABRIR = "comanda:reopen";

    public static final String FINANCEIRO_LER_PROPRIO = "financeiro:read:own";
    public static final String FINANCEIRO_LER_TODOS = "financeiro:read:all";
    public static final String FINANCEIRO_FECHAR = "financeiro:close";
    public static final String FINANCEIRO_CONCILIAR = "financeiro:reconcile";

    public static final String ESTOQUE_LER = "estoque:read";
    public static final String ESTOQUE_ESCREVER = "estoque:write";
    public static final String PRODUTO_PRECO_ESCREVER = "produto:price:write";

    public static final String CLIENTE_LER = "cliente:read";
    public static final String CLIENTE_ESCREVER = "cliente:write";
    /** Separada porque ficha de química indica alergia — dado de saúde (LGPD). */
    public static final String CLIENTE_FICHA_LER = "cliente:ficha:read";

    public static final String USUARIO_GERENCIAR = "usuario:manage";
    public static final String CONFIG_GERENCIAR = "config:manage";
    public static final String RELATORIO_LER = "relatorio:read";
}
