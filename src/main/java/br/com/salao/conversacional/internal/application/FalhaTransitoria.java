package br.com.salao.conversacional.internal.application;

/**
 * RT-CNV-003 — a integração falhou, e tentar de novo mais tarde pode resolver.
 *
 * <p>Rede, timeout, 5xx, limite de taxa, token recusado (que se conserta configurando). A linha volta
 * à fila com espera crescente, até a quinta.
 */
public class FalhaTransitoria extends RuntimeException {

    public FalhaTransitoria(String mensagem) {
        super(mensagem);
    }

    public FalhaTransitoria(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
