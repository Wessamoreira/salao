package br.com.salao.shared.agente;

import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.erro.ErrosDaInfra;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * RT-CNV-005 — ler os argumentos que o modelo mandou, como uma rota HTTP leria o corpo.
 *
 * <p>São entrada NÃO confiável (RN-CNV-010). Cada leitura confere presença, tipo e formato, e recusa
 * com {@code ER-INF-DADOS_INVALIDOS}: o erro volta ao modelo com o NOME do campo, para ele corrigir.
 * Nunca com o valor recebido — o valor é do modelo, e pode ser texto de ataque indo parar em log.
 *
 * <p>Extraído quando a terceira ferramenta precisou das mesmas leituras.
 */
public final class ArgumentosDaFerramenta {

    /** {@code UUID.fromString} aceita "1-1-1-1-1"; um id que o modelo inventou não passa por aqui. */
    private static final Pattern UUID_CANONICO =
            Pattern.compile("[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}");

    private ArgumentosDaFerramenta() {
    }

    /** Texto obrigatório, sem espaço nas pontas, com teto de tamanho. */
    public static String texto(JsonNode argumentos, String campo, int maximo) {
        JsonNode no = campo(argumentos, campo);
        if (no == null || !no.isString() || no.asString().isBlank()) {
            throw invalido(campo, "é obrigatório e precisa ser texto");
        }
        String valor = no.asString().strip();
        if (valor.length() > maximo) {
            throw invalido(campo, "passa de " + maximo + " caracteres");
        }
        return valor;
    }

    public static UUID uuid(JsonNode argumentos, String campo) {
        UUID valor = uuidOpcional(argumentos, campo);
        if (valor == null) {
            throw invalido(campo, "é obrigatório");
        }
        return valor;
    }

    /** Ausente, nulo ou vazio é {@code null}; presente e fora do formato é recusa. */
    public static UUID uuidOpcional(JsonNode argumentos, String campo) {
        JsonNode no = campo(argumentos, campo);
        if (no == null || (no.isString() && no.asString().isBlank())) {
            return null;
        }
        if (!no.isString() || !UUID_CANONICO.matcher(no.asString().strip()).matches()) {
            throw invalido(campo, "precisa ser um id");
        }
        return UUID.fromString(no.asString().strip());
    }

    /** {@code AAAA-MM-DD}. */
    public static LocalDate data(JsonNode argumentos, String campo) {
        String valor = texto(argumentos, campo, 10);
        try {
            return LocalDate.parse(valor);
        } catch (DateTimeParseException e) {
            throw invalido(campo, "precisa ser uma data AAAA-MM-DD");
        }
    }

    /**
     * {@code AAAA-MM-DDTHH:MM}, sem fuso — a hora é a do salão.
     *
     * <p>Com {@code Z} ou deslocamento é recusado de propósito: o modelo convertendo para UTC é a forma
     * mais provável de uma marcação sair três horas errada.
     */
    public static LocalDateTime dataHora(JsonNode argumentos, String campo) {
        String valor = texto(argumentos, campo, 19);
        try {
            return LocalDateTime.parse(valor);
        } catch (DateTimeParseException e) {
            throw invalido(campo, "precisa ser data e hora do salão, AAAA-MM-DDTHH:MM, sem fuso");
        }
    }

    private static JsonNode campo(JsonNode argumentos, String campo) {
        if (argumentos == null || !argumentos.isObject()) {
            return null;
        }
        JsonNode no = argumentos.path(campo);
        return no.isMissingNode() || no.isNull() ? null : no;
    }

    private static ErroDeDominio invalido(String campo, String problema) {
        return new ErroDeDominio(ErrosDaInfra.DADOS_INVALIDOS, campo + " " + problema);
    }
}
