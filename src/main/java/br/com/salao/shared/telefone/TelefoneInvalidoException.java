package br.com.salao.shared.telefone;

/** RT-CLI-001 — erro de domínio puro, traduzido na camada de aplicação. */
public class TelefoneInvalidoException extends RuntimeException {
    public TelefoneInvalidoException(String motivo) {
        super(motivo);
    }
}
