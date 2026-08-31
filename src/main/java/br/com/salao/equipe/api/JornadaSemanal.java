package br.com.salao.equipe.api;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * RT-EQP-002 — os dias e horários em que um profissional trabalha, numa semana comum.
 *
 * <p>É o primeiro insumo da consulta de disponibilidade (RT-AGD-001): o que sobra depois de tirar
 * bloqueios e horários já ocupados.
 *
 * <p>Semana <strong>comum</strong>: feriado, férias, curso e atestado são exceções, e vivem em
 * RT-EQP-003. Misturar as duas coisas faria a jornada crescer com datas e deixaria de responder à
 * pergunta simples — "que dias você trabalha?" — que é a que o salão faz.
 */
public record JornadaSemanal(Map<DayOfWeek, List<IntervaloDeTrabalho>> porDia) {

    public JornadaSemanal {
        // Cópia imutável e ordenada: quem recebe não altera a jornada de quem forneceu, e a
        // ordem dos dias é a da semana, não a de inserção no mapa.
        var copia = new EnumMap<DayOfWeek, List<IntervaloDeTrabalho>>(DayOfWeek.class);
        porDia.forEach((dia, intervalos) -> copia.put(dia, List.copyOf(intervalos)));
        porDia = Map.copyOf(copia);
    }

    public static JornadaSemanal vazia() {
        return new JornadaSemanal(Map.of());
    }

    public List<IntervaloDeTrabalho> em(DayOfWeek dia) {
        return porDia.getOrDefault(dia, List.of());
    }

    /** Se o profissional atende neste dia e hora, numa semana sem exceções. */
    public boolean trabalhaEm(DayOfWeek dia, LocalTime hora) {
        return em(dia).stream().anyMatch(intervalo -> intervalo.contem(hora));
    }

    /** Quanto tempo de atendimento a semana oferece. Base do cálculo de ocupação. */
    public int minutosNaSemana() {
        return porDia.values().stream()
                .flatMap(List::stream)
                .mapToInt(IntervaloDeTrabalho::minutos)
                .sum();
    }

    public boolean estaVazia() {
        return porDia.values().stream().allMatch(List::isEmpty);
    }
}
