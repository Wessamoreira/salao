package br.com.salao.cliente.internal.domain;

/** RT-CLI-001 — erro de domínio puro, traduzido na camada de aplicação. */
public class TelefoneInvalidoException extends RuntimeException {
    public TelefoneInvalidoException(String motivo) {
        super(motivo);
    }
}
