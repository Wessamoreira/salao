package br.com.salao.iam.internal.domain;

/**
 * RT-IAM-001 — erro de domínio puro, sem acoplamento a HTTP.
 *
 * <p>É traduzido para {@code ER-IAM-DADOS_INVALIDOS} na camada de aplicação. O domínio não conhece
 * status HTTP, e é essa separação que permite testá-lo sem subir contexto.
 */
public class DadosDoEstabelecimentoInvalidosException extends RuntimeException {

    public DadosDoEstabelecimentoInvalidosException(String motivo) {
        super(motivo);
    }
}
