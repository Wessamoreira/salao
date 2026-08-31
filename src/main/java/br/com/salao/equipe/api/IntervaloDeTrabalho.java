package br.com.salao.equipe.api;

import java.time.LocalTime;

/**
 * RT-EQP-002 — um pedaço do dia em que o profissional atende.
 *
 * <p>O almoço não aparece aqui, e é de propósito: ele é a <strong>ausência</strong> de um
 * intervalo. Uma jornada de "9h às 19h com almoço das 13h às 14h" são dois intervalos, não um
 * intervalo com um buraco nomeado — e é isso que faz três blocos, ou dois intervalos, ou uma
 * quarta-feira só de manhã caberem sem inventar campo novo.
 *
 * <p>{@link LocalTime} e não instante: a jornada é "toda terça das 9h às 19h", não uma data. A
 * conversão para instante acontece na borda, com o fuso do estabelecimento (ADR-0009).
 */
public record IntervaloDeTrabalho(LocalTime inicio, LocalTime fim) {

    public IntervaloDeTrabalho {
        if (inicio == null || fim == null) {
            throw new IllegalArgumentException("início e fim são obrigatórios");
        }
        if (!fim.isAfter(inicio)) {
            throw new IllegalArgumentException("o fim precisa ser depois do início: "
                    + inicio + " a " + fim);
        }
    }

    public boolean sobrepoe(IntervaloDeTrabalho outro) {
        return inicio.isBefore(outro.fim) && outro.inicio.isBefore(fim);
    }

    /** Se o profissional atende neste horário. O fim é exclusivo: às 19h ele já foi embora. */
    public boolean contem(LocalTime hora) {
        return !hora.isBefore(inicio) && hora.isBefore(fim);
    }

    public int minutos() {
        return (fim.toSecondOfDay() - inicio.toSecondOfDay()) / 60;
    }
}
