package br.com.salao.equipe.internal.domain;

/** RT-EQP-001 — erro de domínio puro, traduzido na camada de aplicação. */
public class DocumentoInvalidoException extends RuntimeException {
    public DocumentoInvalidoException(String motivo) {
        super(motivo);
    }
}
