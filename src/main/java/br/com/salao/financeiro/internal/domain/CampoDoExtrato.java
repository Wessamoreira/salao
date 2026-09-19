package br.com.salao.financeiro.internal.domain;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * RT-FIN-009 — o que a conciliação precisa de cada venda, e como a coluna costuma se chamar.
 *
 * <p>Os apelidos são <strong>sugestão</strong>, não contrato: a Stone não publica as colunas da Lista de
 * vendas, e quem confirma qual coluna é qual é o dono, na prévia. Por isso a comparação é por nome
 * normalizado e exato — "data" não casa com "data de pagamento" —, e um apelido a mais custa uma
 * sugestão errada que a prévia mostra, nunca um valor gravado errado.
 *
 * <p>A ordem importa: cada campo escolhe a primeira coluna livre que casar, na ordem do enum.
 */
public enum CampoDoExtrato {

    ID_NA_ADQUIRENTE(true, "Stone ID",
            "stone id", "stoneid", "id stone", "id da transacao", "codigo da transacao",
            "id da venda"),
    DATA(true, "Data da venda",
            "data da venda", "data e hora da venda", "data hora da venda", "data e hora",
            "data hora", "data da transacao", "data de captura", "data da captura", "data"),
    VALOR_BRUTO(true, "Valor bruto",
            "valor bruto", "valor bruto da venda", "valor da venda", "valor total", "valor"),
    VALOR_LIQUIDO(false, "Valor líquido",
            "valor liquido", "valor liquido da venda"),
    PRODUTO(false, "Crédito ou débito",
            "produto", "tipo de pagamento", "forma de pagamento", "meio de pagamento",
            "modalidade", "tipo"),
    BANDEIRA(false, "Bandeira",
            "bandeira", "bandeira do cartao"),
    PARCELAS(false, "Parcelas",
            "parcelas", "n de parcelas", "no de parcelas", "numero de parcelas",
            "quantidade de parcelas", "qtd de parcelas", "qtd parcelas"),
    NSU(false, "NSU",
            "nsu"),
    AUTORIZACAO(false, "Código de autorização",
            "codigo de autorizacao", "cod de autorizacao", "cod autorizacao", "autorizacao"),
    SITUACAO(false, "Situação",
            "status", "situacao", "status da venda", "situacao da venda");

    private final boolean obrigatorio;
    private final String rotulo;
    private final List<String> apelidos;

    CampoDoExtrato(boolean obrigatorio, String rotulo, String... apelidos) {
        this.obrigatorio = obrigatorio;
        this.rotulo = rotulo;
        this.apelidos = List.of(apelidos);
    }

    /** Sem estes três não há o que conciliar: quem, quando e quanto. */
    public boolean obrigatorio() {
        return obrigatorio;
    }

    public String rotulo() {
        return rotulo;
    }

    List<String> apelidos() {
        return apelidos;
    }

    /**
     * "Nº de Parcelas", "n° de parcelas" e "N de parcelas" são o mesmo nome: minúsculas, sem acento,
     * e tudo o que não é letra ou dígito vira espaço.
     */
    public static String normalizar(String nome) {
        if (nome == null) {
            return "";
        }
        String semAcento = Normalizer.normalize(nome, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return semAcento.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
    }
}
