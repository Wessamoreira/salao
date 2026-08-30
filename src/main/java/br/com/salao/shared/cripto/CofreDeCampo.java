package br.com.salao.shared.cripto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * RT-IAM-005 — cifragem de campo com AES-256-GCM.
 *
 * <p>Existe para o que continua sensível <strong>mesmo dentro do banco</strong>: o segredo TOTP
 * hoje, a ficha técnica do cliente amanhã (dado de saúde, ver {@code 05-seguranca}). Um dump
 * vazado, um backup mal guardado ou um SELECT indevido não podem entregar esses valores em claro.
 *
 * <h2>O que isto protege — e o que não protege</h2>
 *
 * <p>Protege contra quem obtém <em>os dados</em>: cópia do banco, backup, acesso de leitura. Não
 * protege contra quem obtém <em>o processo</em>: com a chave em memória, quem executa código na
 * aplicação decifra. É defesa em profundidade, não substituto de controle de acesso.
 *
 * <p><strong>A chave vive fora do banco</strong> — variável de ambiente hoje, gerenciador de
 * segredos em produção. Guardá-la junto do dado cifrado tornaria o exercício decorativo.
 *
 * <h2>GCM, e não CBC</h2>
 *
 * <p>GCM é autenticado: adulterar o texto cifrado faz a decifragem <em>falhar</em>, em vez de
 * devolver lixo que o código seguiria usando. Num segredo TOTP, lixo silencioso viraria um MFA
 * que recusa todos os códigos sem ninguém entender por quê.
 *
 * <p>O IV é aleatório por operação e guardado junto do texto cifrado. Reutilizar IV em GCM é a
 * falha clássica do modo, e a que quebra a confidencialidade de vez.
 */
public class CofreDeCampo {

    private static final String ALGORITMO = "AES/GCM/NoPadding";
    private static final int BITS_DA_TAG = 128;
    private static final int BYTES_DO_IV = 12;
    private static final int BITS_DA_CHAVE = 256;

    private final SecretKeySpec chave;
    private final SecureRandom aleatorio = new SecureRandom();

    public CofreDeCampo(String chaveBase64) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(chaveBase64 == null ? "" : chaveBase64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.cripto.chave precisa ser Base64 válido", e);
        }
        if (bytes.length * 8 != BITS_DA_CHAVE) {
            // Falhar na subida: uma chave curta produziria cifragem fraca em silêncio, e o
            // problema só apareceria quando já houvesse dado cifrado com ela.
            throw new IllegalStateException(
                    "app.cripto.chave precisa ter 32 bytes (256 bits) em Base64");
        }
        this.chave = new SecretKeySpec(bytes, "AES");
    }

    /** @return {@code IV || texto cifrado}, pronto para uma coluna {@code bytea} */
    public byte[] cifrar(String claro) {
        try {
            byte[] iv = new byte[BYTES_DO_IV];
            aleatorio.nextBytes(iv);

            var cifra = Cipher.getInstance(ALGORITMO);
            cifra.init(Cipher.ENCRYPT_MODE, chave, new GCMParameterSpec(BITS_DA_TAG, iv));
            byte[] cifrado = cifra.doFinal(claro.getBytes(StandardCharsets.UTF_8));

            byte[] saida = new byte[iv.length + cifrado.length];
            System.arraycopy(iv, 0, saida, 0, iv.length);
            System.arraycopy(cifrado, 0, saida, iv.length, cifrado.length);
            return saida;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("falha ao cifrar", e);
        }
    }

    public String decifrar(byte[] armazenado) {
        try {
            byte[] iv = Arrays.copyOfRange(armazenado, 0, BYTES_DO_IV);
            byte[] cifrado = Arrays.copyOfRange(armazenado, BYTES_DO_IV, armazenado.length);

            var cifra = Cipher.getInstance(ALGORITMO);
            cifra.init(Cipher.DECRYPT_MODE, chave, new GCMParameterSpec(BITS_DA_TAG, iv));
            return new String(cifra.doFinal(cifrado), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | ArrayIndexOutOfBoundsException e) {
            // Inclui adulteração: a tag do GCM não fecha e a decifragem falha, em vez de
            // devolver lixo que o código seguiria usando.
            throw new IllegalStateException("falha ao decifrar campo", e);
        }
    }

    /** Utilitário para gerar uma chave nova ao provisionar um ambiente. */
    public static String novaChaveBase64() {
        byte[] bytes = new byte[BITS_DA_CHAVE / 8];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
