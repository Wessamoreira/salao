package br.com.salao.iam.internal.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * RT-IAM-003 — geração e verificação do refresh token.
 *
 * <h2>Opaco, e não JWT</h2>
 *
 * <p>Um JWT é válido enquanto não expira: para invalidá-lo antes disso seria preciso uma lista de
 * revogação consultada a cada uso — ou seja, o banco, que é exatamente o que o JWT existia para
 * evitar. Como o refresh precisa ser revogável por natureza (é ele que a rotação invalida e a
 * detecção de reuso derruba em família), guardar uma referência opaca no banco é mais simples e
 * mais honesto.
 *
 * <p>De quebra, um token opaco não carrega informação: interceptá-lo não revela usuário,
 * estabelecimento nem perfil.
 */
public final class SegredoOpaco {

    /** 256 bits. Abaixo disso, adivinhar deixa de ser impossível e passa a ser caro. */
    private static final int BYTES = 32;

    private static final SecureRandom ALEATORIO = new SecureRandom();
    private static final Base64.Encoder CODIFICADOR = Base64.getUrlEncoder().withoutPadding();

    private SegredoOpaco() {
    }

    public static String gerar() {
        byte[] bytes = new byte[BYTES];
        ALEATORIO.nextBytes(bytes);
        return CODIFICADOR.encodeToString(bytes);
    }

    /**
     * SHA-256 do token, para guardar e procurar.
     *
     * <p>Rápido de propósito — ver a justificativa em {@code V8__refresh_token.sql}. O que protege
     * aqui é a entropia do segredo, não a lentidão do hash.
     */
    public static String hashDe(String segredo) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(segredo.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
