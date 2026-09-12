package br.com.salao.conversacional.internal.domain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RT-CNV-001 / RN-CNV-001 — o que autentica o webhook.
 *
 * <p>Esta rota é pública: não há token, não há cookie, não há tenant. O que separa um webhook da
 * Meta de um POST forjado por qualquer um na internet é <strong>só</strong> o HMAC do cabeçalho
 * {@code X-Hub-Signature-256}, calculado sobre o corpo com o segredo do app.
 *
 * <h2>Sobre os BYTES do corpo, nunca sobre o JSON reserializado</h2>
 *
 * <p>O HMAC é do que veio no fio. Desserializar e serializar de novo muda espaços, ordem de
 * chaves e escape de unicode — e o HMAC do resultado não é o mesmo. Por isso o controller lê
 * {@code byte[]} e a validação acontece <em>antes</em> de qualquer parse: um corpo que não confere
 * não deve nem ser interpretado.
 *
 * <h2>Comparação em tempo constante</h2>
 *
 * <p>{@code MessageDigest.isEqual}, e não {@code equals}. Comparação que sai no primeiro byte
 * diferente vaza, pelo tempo de resposta, quantos bytes iniciais estavam certos — e com um
 * oráculo desses a assinatura se descobre byte a byte, sem nunca conhecer o segredo.
 *
 * <h2>Falha fechada quando não há segredo</h2>
 *
 * <p>Sem segredo configurado, {@link #configurado()} é {@code false} e a rota recusa tudo. O
 * contrário — aceitar porque não há como validar — transformaria um deploy com variável
 * esquecida numa porta aberta para gravar mensagem em nome de qualquer salão.
 */
public final class AssinaturaDoWebhook {

    private static final String ALGORITMO = "HmacSHA256";
    private static final String PREFIXO = "sha256=";

    private final byte[] segredo;

    public AssinaturaDoWebhook(String segredo) {
        this.segredo = segredo == null || segredo.isBlank()
                ? new byte[0]
                : segredo.getBytes(StandardCharsets.UTF_8);
    }

    /** Falso quando o segredo do app não foi configurado. A rota recusa tudo nesse caso. */
    public boolean configurado() {
        return segredo.length > 0;
    }

    /**
     * @param corpo     os bytes exatos do corpo, como chegaram
     * @param cabecalho o valor de {@code X-Hub-Signature-256}, no formato {@code sha256=<hex>}
     */
    public boolean confere(byte[] corpo, String cabecalho) {
        if (!configurado() || corpo == null || cabecalho == null) {
            return false;
        }
        // Comparação de prefixo sem distinção de caixa: o cabeçalho é da Meta, e depender da
        // caixa de "sha256" seria acoplar a um detalhe que eles não prometeram.
        if (!cabecalho.regionMatches(true, 0, PREFIXO, 0, PREFIXO.length())) {
            return false;
        }
        byte[] recebida = deHex(cabecalho.substring(PREFIXO.length()).trim());
        if (recebida.length == 0) {
            return false;
        }
        return MessageDigest.isEqual(recebida, hmac(corpo));
    }

    private byte[] hmac(byte[] corpo) {
        try {
            Mac mac = Mac.getInstance(ALGORITMO);
            mac.init(new SecretKeySpec(segredo, ALGORITMO));
            return mac.doFinal(corpo);
        } catch (GeneralSecurityException e) {
            // HmacSHA256 é obrigatório em toda JVM, e a chave é não-vazia por construção.
            throw new IllegalStateException("HMAC indisponível", e);
        }
    }

    /**
     * Hex para bytes, devolvendo vazio em qualquer sujeira.
     *
     * <p>Sem exceção: cabeçalho malformado é apenas assinatura que não confere, e o chamador já
     * sabe tratar isso. Um {@code throw} aqui viraria 500 — e um 500 numa rota pública é um sinal
     * para quem está sondando.
     */
    private static byte[] deHex(String hex) {
        if (hex.length() % 2 != 0 || hex.isEmpty()) {
            return new byte[0];
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int alto = Character.digit(hex.charAt(i * 2), 16);
            int baixo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (alto < 0 || baixo < 0) {
                return new byte[0];
            }
            bytes[i] = (byte) ((alto << 4) | baixo);
        }
        return bytes;
    }

    /**
     * Compara o token de verificação do handshake, também em tempo constante.
     *
     * <p>Mesmo raciocínio da assinatura: o {@code GET} de verificação é público, e um
     * {@code equals} ali entrega o token por tempo de resposta.
     */
    public static boolean tokenConfere(String esperado, String recebido) {
        if (esperado == null || esperado.isBlank() || recebido == null) {
            return false;
        }
        return MessageDigest.isEqual(
                esperado.getBytes(StandardCharsets.UTF_8),
                recebido.getBytes(StandardCharsets.UTF_8));
    }
}
