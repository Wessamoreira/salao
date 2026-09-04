package br.com.salao.agenda.api;

import java.time.Duration;
import java.time.Instant;

/**
 * RT-AGD-001 — um pedaço de tempo, fechado no início e aberto no fim.
 *
 * <p>O fim aberto é o que faz dois atendimentos encostados <strong>não</strong> conflitarem: um
 * termina às 10h e o outro começa às 10h, e ninguém está em dois lugares. Com o fim fechado, todo
 * horário cheio de agenda seria um conflito — a agenda ficaria com um buraco de um minuto entre
 * cada cliente, e a recepção aprenderia a marcar às 10h01.
 *
 * <p>É a mesma semântica do {@code tstzrange} {@code [inicio, fim)} usado nas constraints, e isso
 * não é coincidência: se o domínio e o banco discordassem sobre o encostar, a aplicação ofereceria
 * horários que o banco recusaria na hora de gravar.
 */
public record Janela(Instant inicio, Instant fim) {

    public Janela {
        if (inicio == null || fim == null) {
            throw new IllegalArgumentException("janela precisa de início e fim");
        }
        if (!fim.isAfter(inicio)) {
            throw new IllegalArgumentException("janela vazia ou invertida: " + inicio + " a " + fim);
        }
    }

    public static Janela de(Instant inicio, Duration duracao) {
        return new Janela(inicio, inicio.plus(duracao));
    }

    /** Se esta janela cabe inteira dentro da outra. */
    public boolean cabeEm(Janela outra) {
        return !inicio.isBefore(outra.inicio) && !fim.isAfter(outra.fim);
    }

    /** Se as duas se cruzam em algum instante. Encostar não é cruzar. */
    public boolean cruzaCom(Janela outra) {
        return inicio.isBefore(outra.fim) && outra.inicio.isBefore(fim);
    }

    public Duration duracao() {
        return Duration.between(inicio, fim);
    }
}
