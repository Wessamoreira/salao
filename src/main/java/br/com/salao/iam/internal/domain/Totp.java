package br.com.salao.iam.internal.domain;

import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RT-IAM-005 — TOTP (RFC 6238) sobre HOTP (RFC 4226).
 *
 * <h2>Por que implementado aqui e não por biblioteca</h2>
 *
 * <p>A regra geral é não escrever primitiva criptográfica à mão, e ela continua valendo: o HMAC
 * vem do JDK. O que está aqui é a parte <em>especificada e verificável</em> — contador por janela
 * de tempo e truncagem dinâmica —, e a RFC publica vetores de teste oficiais. {@code TotpTest}
 * roda esses vetores, então a corretude é <strong>demonstrada</strong>, não presumida.
 *
 * <p>O risco real do TOTP nunca esteve no algoritmo, que é trivial. Está na política de
 * verificação: tamanho da janela e reuso de código. Ambos ficam em {@link #contadorDe} e na
 * checagem de contador do caso de uso — visíveis, e não escondidos numa dependência.
 *
 * <h2>SHA-1 é correto aqui</h2>
 *
 * <p>A RFC 6238 define SHA-1 como padrão e é o que todo aplicativo autenticador implementa. Não é
 * a fraqueza de colisão que importa: o HMAC-SHA1 não depende de resistência a colisão, e o
 * resultado é truncado para seis dígitos válidos por trinta segundos. Trocar por SHA-256 quebraria
 * a compatibilidade com Google Authenticator, Authy e similares, sem ganho prático.
 */
public final class Totp {

    public static final Duration JANELA = Duration.ofSeconds(30);
    public static final int DIGITOS = 6;

    private static final String ALGORITMO = "HmacSHA1";
    private static final int BYTES_DO_SEGREDO = 20;
    private static final SecureRandom ALEATORIO = new SecureRandom();
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private Totp() {
    }

    /** Segredo novo, em Base32 — o formato que os autenticadores leem. */
    public static String novoSegredo() {
        byte[] bytes = new byte[BYTES_DO_SEGREDO];
        ALEATORIO.nextBytes(bytes);
        return base32(bytes);
    }

    /** O contador da janela de tempo. É ele que o caso de uso guarda para impedir reuso. */
    public static long contadorDe(Instant instante) {
        return instante.getEpochSecond() / JANELA.toSeconds();
    }

    public static String gerar(String segredoBase32, long contador) {
        byte[] chave = deBase32(segredoBase32);
        byte[] hash = hmac(chave, ByteBuffer.allocate(8).putLong(contador).array());

        // Truncagem dinâmica (RFC 4226 §5.3): os 4 bits finais escolhem o deslocamento,
        // e o bit mais significativo é descartado para o resultado nunca ser negativo.
        int deslocamento = hash[hash.length - 1] & 0x0F;
        int binario = ((hash[deslocamento] & 0x7F) << 24)
                | ((hash[deslocamento + 1] & 0xFF) << 16)
                | ((hash[deslocamento + 2] & 0xFF) << 8)
                | (hash[deslocamento + 3] & 0xFF);

        int modulo = (int) Math.pow(10, DIGITOS);
        return String.format("%0" + DIGITOS + "d", binario % modulo);
    }

    /**
     * Confere o código nas janelas ao redor de {@code contadorAtual}.
     *
     * @param tolerancia quantas janelas antes e depois aceitar. Uma janela (±30s) cobre relógio
     *                   dessincronizado do celular e o tempo entre ler e digitar. Mais que isso
     *                   multiplica a chance de acerto por tentativa sem melhorar a usabilidade.
     * @return o contador que casou, ou {@code -1} se nenhum casou
     */
    public static long conferir(String segredoBase32, String codigo, long contadorAtual,
                               int tolerancia) {
        if (codigo == null || codigo.length() != DIGITOS) {
            return -1;
        }
        for (long c = contadorAtual - tolerancia; c <= contadorAtual + tolerancia; c++) {
            // Comparação em tempo constante: comparar strings de dígitos com equals vaza,
            // por tempo, quantos caracteres iniciais estavam certos.
            if (constante(gerar(segredoBase32, c), codigo)) {
                return c;
            }
        }
        return -1;
    }

    private static boolean constante(String esperado, String informado) {
        if (esperado.length() != informado.length()) {
            return false;
        }
        int diferenca = 0;
        for (int i = 0; i < esperado.length(); i++) {
            diferenca |= esperado.charAt(i) ^ informado.charAt(i);
        }
        return diferenca == 0;
    }

    private static byte[] hmac(byte[] chave, byte[] dados) {
        try {
            Mac mac = Mac.getInstance(ALGORITMO);
            mac.init(new SecretKeySpec(chave, ALGORITMO));
            return mac.doFinal(dados);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA1 indisponível", e);
        }
    }

    static String base32(byte[] dados) {
        var saida = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : dados) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                saida.append(BASE32[(buffer >> (bits - 5)) & 0x1F]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            saida.append(BASE32[(buffer << (5 - bits)) & 0x1F]);
        }
        return saida.toString();
    }

    static byte[] deBase32(String texto) {
        String limpo = texto.replace("=", "").replace(" ", "").toUpperCase(java.util.Locale.ROOT);
        var saida = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : limpo.toCharArray()) {
            int valor = new String(BASE32).indexOf(c);
            if (valor < 0) {
                throw new IllegalArgumentException("segredo Base32 inválido");
            }
            buffer = (buffer << 5) | valor;
            bits += 5;
            if (bits >= 8) {
                saida.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return saida.toByteArray();
    }
}
