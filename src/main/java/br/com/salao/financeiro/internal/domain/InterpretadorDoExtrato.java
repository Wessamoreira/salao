package br.com.salao.financeiro.internal.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * RT-FIN-009 — cada linha do arquivo vira venda lida, ignorada ou recusada.
 *
 * <h2>Estrito onde é dinheiro e tempo</h2>
 *
 * <p>Data em modo {@code STRICT}: "31/02" é recusada, e não vira 03/03 em silêncio. Valor bruto tem de ser
 * positivo — cancelamento chega pela situação, não por valor negativo. O que não se reconhece é
 * recusado com o próprio valor no motivo, para quem abrir o arquivo achar a célula.
 *
 * <h2>Negativas antes</h2>
 *
 * <p>"Não autorizada" contém "autorizada". A situação confere primeiro o que tira a venda da conta —
 * negada, recusada, pré-autorização — e só depois o que a aprova.
 */
public final class InterpretadorDoExtrato {

    private static final int TAMANHO_MAXIMO_DE_ID = 64;
    private static final int TAMANHO_MAXIMO_DE_CODIGO = 40;

    private static final List<DateTimeFormatter> COM_HORA = formatos(
            "dd/MM/uuuu HH:mm:ss", "dd/MM/uuuu HH:mm", "dd/MM/uu HH:mm:ss", "dd/MM/uu HH:mm",
            "uuuu-MM-dd HH:mm:ss", "uuuu-MM-dd'T'HH:mm:ss", "uuuu-MM-dd HH:mm",
            "dd-MM-uuuu HH:mm:ss", "dd-MM-uuuu HH:mm");
    private static final List<DateTimeFormatter> SO_DATA = formatos(
            "dd/MM/uuuu", "dd/MM/uu", "uuuu-MM-dd", "dd-MM-uuuu");

    private InterpretadorDoExtrato() {
    }

    public static List<LinhaDoExtrato> interpretar(LeitorDeCsv.Tabela tabela,
                                                   MapeamentoDoExtrato mapeamento) {
        Map<CampoDoExtrato, Integer> indices = new EnumMap<>(CampoDoExtrato.class);
        for (CampoDoExtrato campo : CampoDoExtrato.values()) {
            indices.put(campo, mapeamento.indice(campo, tabela.cabecalho()));
        }
        var linhas = new ArrayList<LinhaDoExtrato>();
        int colunas = tabela.cabecalho().size();
        for (LeitorDeCsv.Registro registro : tabela.registros()) {
            if (registro.campos().size() != colunas) {
                linhas.add(new LinhaDoExtrato.Recusada(registro.linha(), "a linha tem "
                        + registro.campos().size() + " colunas; o cabeçalho tem " + colunas));
                continue;
            }
            linhas.add(interpretar(registro, indices, mapeamento.formato()));
        }
        return linhas;
    }

    static LinhaDoExtrato interpretar(LeitorDeCsv.Registro registro,
                                      Map<CampoDoExtrato, Integer> indices,
                                      FormatoDeNumero formato) {
        int linha = registro.linha();
        try {
            SituacaoLida situacao = situacao(valor(registro, indices, CampoDoExtrato.SITUACAO));
            if (situacao.ignorar() != null) {
                return new LinhaDoExtrato.Ignorada(linha, situacao.ignorar());
            }
            String textoDoProduto = valor(registro, indices, CampoDoExtrato.PRODUTO);
            if (textoDoProduto != null && CampoDoExtrato.normalizar(textoDoProduto).contains("pix")) {
                return new LinhaDoExtrato.Ignorada(linha, "Pix não passa pela conciliação");
            }

            String id = valor(registro, indices, CampoDoExtrato.ID_NA_ADQUIRENTE);
            if (id == null) {
                return new LinhaDoExtrato.Recusada(linha, "sem Stone ID");
            }
            if (id.length() > TAMANHO_MAXIMO_DE_ID) {
                return new LinhaDoExtrato.Recusada(linha, "Stone ID com mais de "
                        + TAMANHO_MAXIMO_DE_ID + " caracteres");
            }

            String textoDaData = valor(registro, indices, CampoDoExtrato.DATA);
            if (textoDaData == null) {
                return new LinhaDoExtrato.Recusada(linha, "sem data da venda");
            }
            DataLida data = data(textoDaData);
            if (data == null) {
                return new LinhaDoExtrato.Recusada(linha, "data não reconhecida: " + textoDaData);
            }

            BigDecimal bruto = formato.ler(valor(registro, indices, CampoDoExtrato.VALOR_BRUTO));
            if (bruto == null) {
                return new LinhaDoExtrato.Recusada(linha, "sem valor bruto");
            }
            if (bruto.signum() <= 0) {
                return new LinhaDoExtrato.Recusada(linha, "valor bruto precisa ser positivo: "
                        + Reais.de(bruto) + " — cancelamento vem pela situação");
            }
            BigDecimal liquido = formato.ler(valor(registro, indices, CampoDoExtrato.VALOR_LIQUIDO));
            if (liquido != null && liquido.signum() < 0) {
                return new LinhaDoExtrato.Recusada(linha, "valor líquido negativo: "
                        + Reais.de(liquido));
            }

            Integer parcelas = parcelas(valor(registro, indices, CampoDoExtrato.PARCELAS));
            if (parcelas == null) {
                return new LinhaDoExtrato.Recusada(linha, "parcelas não reconhecidas: "
                        + valor(registro, indices, CampoDoExtrato.PARCELAS));
            }

            String bandeira = valor(registro, indices, CampoDoExtrato.BANDEIRA);
            String nsu = valor(registro, indices, CampoDoExtrato.NSU);
            String autorizacao = valor(registro, indices, CampoDoExtrato.AUTORIZACAO);
            for (String codigo : new String[] { bandeira, nsu, autorizacao }) {
                if (codigo != null && codigo.length() > TAMANHO_MAXIMO_DE_CODIGO) {
                    return new LinhaDoExtrato.Recusada(linha, "campo com mais de "
                            + TAMANHO_MAXIMO_DE_CODIGO + " caracteres: " + codigo);
                }
            }

            return new LinhaDoExtrato.Lida(linha, new TransacaoLida(id, data.quando(),
                    data.comHora(), produto(textoDoProduto),
                    bandeira == null ? null : bandeira.toUpperCase(java.util.Locale.ROOT),
                    parcelas, bruto, liquido, nsu, autorizacao, situacao.situacao()));
        } catch (IllegalArgumentException e) {
            return new LinhaDoExtrato.Recusada(linha, e.getMessage());
        }
    }

    private static String valor(LeitorDeCsv.Registro registro,
                                Map<CampoDoExtrato, Integer> indices, CampoDoExtrato campo) {
        int i = indices.getOrDefault(campo, -1);
        if (i < 0) {
            return null;
        }
        String texto = registro.campos().get(i).strip();
        return texto.isEmpty() ? null : texto;
    }

    private record DataLida(LocalDateTime quando, boolean comHora) {
    }

    static DataLida data(String texto) {
        String t = texto.strip().replace(" às ", " ").replace(" as ", " ").replaceAll("\\s+", " ");
        for (DateTimeFormatter formato : COM_HORA) {
            try {
                return new DataLida(LocalDateTime.parse(t, formato), true);
            } catch (DateTimeParseException e) {
                // tenta o próximo
            }
        }
        for (DateTimeFormatter formato : SO_DATA) {
            try {
                return new DataLida(LocalDate.parse(t, formato).atStartOfDay(), false);
            } catch (DateTimeParseException e) {
                // tenta o próximo
            }
        }
        return null;
    }

    /** "3", "3x", "3 parcelas", "À vista" ou vazio. Nulo se não for nada disso. */
    static Integer parcelas(String texto) {
        if (texto == null) {
            return 1;
        }
        String t = CampoDoExtrato.normalizar(texto);
        if (t.isEmpty() || t.equals("a vista") || t.equals("avista")) {
            return 1;
        }
        String digitos = t.replaceAll("^(\\d{1,2})\\s*(x|parcela|parcelas)?$", "$1");
        if (!digitos.matches("\\d{1,2}") || Integer.parseInt(digitos) < 1) {
            return null;
        }
        return Integer.parseInt(digitos);
    }

    static ProdutoDoCartao produto(String texto) {
        if (texto == null) {
            return null;
        }
        String t = CampoDoExtrato.normalizar(texto);
        if (t.contains("credito") || t.contains("credit")) {
            return ProdutoDoCartao.CREDITO;
        }
        if (t.contains("debito") || t.contains("debit")) {
            return ProdutoDoCartao.DEBITO;
        }
        return ProdutoDoCartao.OUTRO;
    }

    /** @param ignorar o motivo, quando a linha não é venda a conciliar */
    private record SituacaoLida(SituacaoDaTransacao situacao, String ignorar) {
    }

    static SituacaoLida situacao(String texto) {
        if (texto == null) {
            return new SituacaoLida(SituacaoDaTransacao.APROVADA, null);
        }
        String t = CampoDoExtrato.normalizar(texto);
        if (t.contains("nao autoriz") || t.contains("negad") || t.contains("recusad")) {
            return new SituacaoLida(null, "venda não aprovada: " + texto.strip());
        }
        if (t.contains("pre autoriz")) {
            return new SituacaoLida(null, "pré-autorização não é venda: " + texto.strip());
        }
        if (t.contains("cancel") || t.contains("estorn") || t.contains("chargeback")
                || t.contains("contest")) {
            return new SituacaoLida(SituacaoDaTransacao.CANCELADA, null);
        }
        if (t.contains("aprov") || t.contains("autoriz") || t.contains("captur") || t.contains("pag")
                || t.contains("conclu") || t.contains("liquid") || t.contains("confirm")) {
            return new SituacaoLida(SituacaoDaTransacao.APROVADA, null);
        }
        throw new IllegalArgumentException("situação não reconhecida: " + texto.strip());
    }

    private static List<DateTimeFormatter> formatos(String... padroes) {
        var lista = new ArrayList<DateTimeFormatter>();
        for (String padrao : padroes) {
            lista.add(DateTimeFormatter.ofPattern(padrao).withResolverStyle(ResolverStyle.STRICT));
        }
        return List.copyOf(lista);
    }
}
