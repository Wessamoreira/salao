package br.com.salao.financeiro.internal.domain;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RT-FIN-009 — lê um CSV exportado de portal, sem supor quase nada sobre ele.
 *
 * <ul>
 *   <li><strong>Codificação:</strong> UTF-8, com ou sem BOM; se não for UTF-8 válido, Windows-1252 — que
 *       é o que o Excel brasileiro grava, e o que faria "Situação" virar "SituaÃ§Ã£o";
 *   <li><strong>Separador:</strong> ponto e vírgula, vírgula ou tabulação — o que mais aparece fora de
 *       aspas nas primeiras linhas;
 *   <li><strong>Aspas:</strong> RFC 4180, com aspas dobradas e quebra de linha dentro do campo;
 *   <li><strong>Cabeçalho:</strong> a primeira linha com o número de colunas mais comum no arquivo. Portal
 *       costuma pôr título e período antes; eles ficam para trás.
 * </ul>
 *
 * <p>Planilha do Excel é recusada dizendo o que fazer: ler .xlsx pediria uma biblioteca de planilha, e
 * o portal exporta em CSV.
 */
public final class LeitorDeCsv {

    public static final int REGISTROS_MAXIMOS = 20_000;
    private static final int COLUNAS_MAXIMAS = 80;
    private static final char[] SEPARADORES = { ';', ',', '\t' };

    private LeitorDeCsv() {
    }

    /** @param linha a linha do arquivo em que o registro começa, contando de 1 */
    public record Registro(int linha, List<String> campos) {
    }

    public record Tabela(List<String> cabecalho, List<Registro> registros, char separador,
                         String codificacao) {
    }

    public static Tabela ler(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ArquivoDeExtratoInvalidoException("o arquivo está vazio");
        }
        if (bytes.length >= 2 && bytes[0] == 'P' && bytes[1] == 'K') {
            throw new ArquivoDeExtratoInvalidoException(
                    "é uma planilha do Excel (.xlsx) — exporte a Lista de vendas em CSV");
        }
        if (bytes.length >= 4 && (bytes[0] & 0xFF) == 0xD0 && (bytes[1] & 0xFF) == 0xCF
                && (bytes[2] & 0xFF) == 0x11 && (bytes[3] & 0xFF) == 0xE0) {
            throw new ArquivoDeExtratoInvalidoException(
                    "é uma planilha do Excel (.xls) — exporte a Lista de vendas em CSV");
        }

        String codificacao = "UTF-8";
        String texto;
        try {
            texto = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            codificacao = "windows-1252";
            texto = new String(bytes, Charset.forName("windows-1252"));
        }
        if (texto.startsWith("﻿")) {
            texto = texto.substring(1);
        }

        char separador = separador(texto);
        List<Registro> todos = registros(texto, separador);

        int colunas = colunasMaisComuns(todos);
        if (colunas < 2) {
            throw new ArquivoDeExtratoInvalidoException(
                    "não encontrei colunas — o arquivo é um CSV?");
        }
        if (colunas > COLUNAS_MAXIMAS) {
            throw new ArquivoDeExtratoInvalidoException(
                    "o arquivo tem " + colunas + " colunas, mais do que uma lista de vendas");
        }
        int inicio = 0;
        while (todos.get(inicio).campos().size() != colunas) {
            inicio++;
        }
        List<String> cabecalho = todos.get(inicio).campos().stream().map(String::strip).toList();

        List<Registro> dados = new ArrayList<>();
        for (Registro registro : todos.subList(inicio + 1, todos.size())) {
            if (registro.campos().stream().allMatch(String::isBlank)) {
                continue;
            }
            dados.add(registro);
        }
        if (dados.size() > REGISTROS_MAXIMOS) {
            throw new ArquivoDeExtratoInvalidoException("o arquivo tem " + dados.size()
                    + " vendas; o máximo por importação é " + REGISTROS_MAXIMOS
                    + " — exporte um período menor");
        }
        return new Tabela(cabecalho, List.copyOf(dados), separador, codificacao);
    }

    /** O separador que mais aparece fora de aspas nas primeiras dez linhas com conteúdo. */
    static char separador(String texto) {
        int[] contagem = new int[SEPARADORES.length];
        int linhas = 0;
        boolean entreAspas = false;
        boolean linhaTemConteudo = false;
        for (int i = 0; i < texto.length() && linhas < 10; i++) {
            char c = texto.charAt(i);
            if (c == '"') {
                entreAspas = !entreAspas;
            } else if (!entreAspas && (c == '\n' || c == '\r')) {
                if (linhaTemConteudo) {
                    linhas++;
                }
                linhaTemConteudo = false;
                continue;
            }
            if (!Character.isWhitespace(c)) {
                linhaTemConteudo = true;
            }
            if (!entreAspas) {
                for (int s = 0; s < SEPARADORES.length; s++) {
                    if (c == SEPARADORES[s]) {
                        contagem[s]++;
                    }
                }
            }
        }
        int melhor = 0;
        for (int s = 1; s < SEPARADORES.length; s++) {
            if (contagem[s] > contagem[melhor]) {
                melhor = s;
            }
        }
        if (contagem[melhor] == 0) {
            throw new ArquivoDeExtratoInvalidoException(
                    "não encontrei separador de colunas — o arquivo é um CSV?");
        }
        return SEPARADORES[melhor];
    }

    static List<Registro> registros(String texto, char separador) {
        List<Registro> registros = new ArrayList<>();
        List<String> campos = new ArrayList<>();
        var campo = new StringBuilder();
        boolean entreAspas = false;
        int linha = 1;
        int inicioDoRegistro = 1;
        int i = 0;
        while (i < texto.length()) {
            char c = texto.charAt(i);
            if (entreAspas) {
                if (c == '"') {
                    if (i + 1 < texto.length() && texto.charAt(i + 1) == '"') {
                        campo.append('"');
                        i++;
                    } else {
                        entreAspas = false;
                    }
                } else {
                    if (c == '\n') {
                        linha++;
                    }
                    campo.append(c);
                }
            } else if (c == '"' && campo.isEmpty()) {
                entreAspas = true;
            } else if (c == separador) {
                campos.add(campo.toString());
                campo.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < texto.length() && texto.charAt(i + 1) == '\n') {
                    i++;
                }
                campos.add(campo.toString());
                campo.setLength(0);
                registros.add(new Registro(inicioDoRegistro, List.copyOf(campos)));
                campos.clear();
                linha++;
                inicioDoRegistro = linha;
                if (registros.size() > REGISTROS_MAXIMOS + 50) {
                    throw new ArquivoDeExtratoInvalidoException("o arquivo tem mais de "
                            + REGISTROS_MAXIMOS + " vendas — exporte um período menor");
                }
            } else {
                campo.append(c);
            }
            i++;
        }
        if (!campo.isEmpty() || !campos.isEmpty()) {
            campos.add(campo.toString());
            registros.add(new Registro(inicioDoRegistro, List.copyOf(campos)));
        }
        return registros;
    }

    /**
     * O número de colunas que mais se repete; no empate, o que aparece primeiro no arquivo.
     *
     * <p>O desempate importa em arquivo pequeno: com o cabeçalho e uma única venda de tamanho
     * diferente, cada contagem aparece uma vez — e escolher a maior fazia da venda o cabeçalho.
     */
    private static int colunasMaisComuns(List<Registro> registros) {
        Map<Integer, Integer> frequencia = new HashMap<>();
        Map<Integer, Integer> primeiraVez = new HashMap<>();
        for (int i = 0; i < registros.size(); i++) {
            int n = registros.get(i).campos().size();
            if (n >= 2) {
                frequencia.merge(n, 1, Integer::sum);
                primeiraVez.putIfAbsent(n, i);
            }
        }
        return frequencia.keySet().stream()
                .max(java.util.Comparator.<Integer>comparingInt(frequencia::get)
                        .thenComparing(n -> -primeiraVez.get(n)))
                .orElse(0);
    }
}
