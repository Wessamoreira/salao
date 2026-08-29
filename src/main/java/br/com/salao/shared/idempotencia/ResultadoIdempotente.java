package br.com.salao.shared.idempotencia;

/**
 * RT-INF-005 — o resultado da operação e se ele veio de uma repetição.
 *
 * <p>{@code repeticao} alimenta o cabeçalho {@code Idempotent-Replay: true}. Vale expor: sem ele,
 * o cliente não distingue "criei agora" de "já existia", e é justamente essa distinção que ele
 * precisa para não contar duas vezes.
 */
public record ResultadoIdempotente<T>(T valor, boolean repeticao) {

    static <T> ResultadoIdempotente<T> novo(T valor) {
        return new ResultadoIdempotente<>(valor, false);
    }

    static <T> ResultadoIdempotente<T> repetido(T valor) {
        return new ResultadoIdempotente<>(valor, true);
    }
}
