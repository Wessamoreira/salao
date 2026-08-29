package br.com.salao.shared.paginacao;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * RT-INF-004 — cursor opaco de paginação por keyset.
 *
 * <p>Opaco de propósito: o cliente não deve construir nem interpretar o conteúdo. Se ele passar a
 * depender do formato, mudar a ordenação vira quebra de contrato.
 *
 * <p><strong>Não é criptografado nem assinado</strong> — é só base64. Nunca coloque aqui nada que
 * o usuário não possa ver, e nunca confie no valor para autorização: o backend refiltra por tenant
 * e por escopo de qualquer forma.
 */
public record Cursor(String conteudo) {

    /** US (unit separator). Não aparece em UUID, data ISO nem nome — os campos que ordenamos. */
    private static final String SEPARADOR = String.valueOf((char) 31);

    private static final Base64.Encoder CODIFICADOR = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODIFICADOR = Base64.getUrlDecoder();

    public static Cursor de(String... valoresOrdenados) {
        return new Cursor(String.join(SEPARADOR, valoresOrdenados));
    }

    public String[] valores() {
        return conteudo.split(SEPARADOR, -1);
    }

    public String codificar() {
        return CODIFICADOR.encodeToString(conteudo.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decodificar(String codificado) {
        try {
            return new Cursor(new String(DECODIFICADOR.decode(codificado), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new CursorInvalidoException(codificado);
        }
    }

    public static class CursorInvalidoException extends IllegalArgumentException {
        public CursorInvalidoException(String valor) {
            super("Cursor de paginação inválido: " + valor);
        }
    }
}
