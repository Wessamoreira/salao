package br.com.salao.equipe.api;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * RT-EQP-003 — um período em que não se atende.
 *
 * @param profissionalId {@code null} significa <strong>o salão inteiro</strong>. Feriado não é
 *                       exceção de uma pessoa, e modelá-lo por profissional faria o salão
 *                       aparecer aberto para quem foi contratado depois de o feriado ser lançado.
 * @param horaInicio     {@code null} em ambas as horas significa dia inteiro. Com hora, o bloqueio
 *                       vale para um único dia — férias são dias cheios, curso é um pedaço de um
 *                       dia, e "das 14h às 18h por uma quinzena" ninguém pede.
 */
public record BloqueioDeJornada(
        UUID id,
        UUID profissionalId,
        MotivoDoBloqueio motivo,
        String observacao,
        LocalDate dataInicio,
        LocalDate dataFim,
        LocalTime horaInicio,
        LocalTime horaFim) {

    public boolean ehDiaInteiro() {
        return horaInicio == null;
    }

    public boolean ehDoSalaoInteiro() {
        return profissionalId == null;
    }

    public boolean alcanca(LocalDate data) {
        return !data.isBefore(dataInicio) && !data.isAfter(dataFim);
    }

    /**
     * A faixa de horas que este bloqueio remove no dia informado.
     *
     * <p>Dia inteiro devolve o dia todo — de meia-noite a um segundo antes da meia-noite seguinte.
     * Não usa {@code 00:00} nas duas pontas porque um intervalo de duração zero não removeria
     * nada, e o dia inteiro é justamente o caso que remove tudo.
     */
    public IntervaloDeTrabalho faixaEm(LocalDate data) {
        if (!alcanca(data)) {
            return null;
        }
        return ehDiaInteiro()
                ? new IntervaloDeTrabalho(LocalTime.MIN, LocalTime.MAX)
                : new IntervaloDeTrabalho(horaInicio, horaFim);
    }
}
