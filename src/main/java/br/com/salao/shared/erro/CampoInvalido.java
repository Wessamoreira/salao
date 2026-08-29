package br.com.salao.shared.erro;

/** RT-INF-003 — um campo rejeitado, com o mesmo contrato de código estável do erro geral. */
public record CampoInvalido(String campo, String codigo, String mensagem) {
}
