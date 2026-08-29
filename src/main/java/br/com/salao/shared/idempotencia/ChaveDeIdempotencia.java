package br.com.salao.shared.idempotencia;

import java.util.Objects;

/**
 * RT-INF-005 — identifica uma tentativa de escrita.
 *
 * <p>O {@code escopo} é a operação ({@code "criar-agendamento"}). Sem ele, a mesma chave usada em
 * endpoints diferentes colidiria e o cliente receberia a resposta de outra operação — o oposto do
 * que a idempotência promete.
 *
 * @param escopo identificador estável da operação; nunca a rota, que muda com a versão da API
 * @param valor  o valor do cabeçalho {@code Idempotency-Key}
 */
public record ChaveDeIdempotencia(String escopo, String valor) {

    public ChaveDeIdempotencia {
        Objects.requireNonNull(escopo, "escopo é obrigatório");
        Objects.requireNonNull(valor, "valor é obrigatório");
        if (escopo.isBlank() || valor.isBlank()) {
            throw new IllegalArgumentException("escopo e valor não podem ser vazios");
        }
        if (valor.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key acima de 255 caracteres");
        }
    }
}
