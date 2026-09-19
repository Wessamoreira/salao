package br.com.salao.financeiro.internal.domain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * RT-FIN-009 — o SHA-256 do arquivo importado.
 *
 * <p>O arquivo não fica guardado — a Lista de vendas pode trazer o cartão mascarado, e a conciliação
 * não precisa dele. Fica a impressão: prova de qual arquivo entrou, e o jeito de avisar "este arquivo já
 * foi importado em …" antes de alguém achar que importou algo novo.
 */
public final class ImpressaoDoArquivo {

    private ImpressaoDoArquivo() {
    }

    public static String de(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
