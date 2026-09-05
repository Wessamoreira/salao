package br.com.salao.agenda.api;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * RT-AGD-010 — alguém esperando uma vaga.
 *
 * <p>A janela é um <strong>intervalo de dias</strong> mais uma faixa de hora opcional, e não um
 * horário exato. "Quinta que vem à tarde" é o que a cliente diz; exigir 14h ou 14h30
 * transformaria a fila numa lista de horários específicos que quase nunca abrem exatamente.
 *
 * @param profissionalId quem ela quer, ou {@code null} para "qualquer um" — que é o caso em que a
 *                       fila mais funciona, porque qualquer cancelamento serve
 */
public record EsperaNaFila(
        UUID id,
        UUID clienteId,
        UUID servicoId,
        UUID profissionalId,
        LocalDate aceitaDe,
        LocalDate aceitaAte,
        LocalTime horaDe,
        LocalTime horaAte,
        String observacao,
        Instant criadoEm) {

    public EsperaNaFila {
        if (clienteId == null || servicoId == null) {
            throw new IllegalArgumentException("a espera precisa de cliente e serviço");
        }
        if (aceitaDe == null || aceitaAte == null || aceitaAte.isBefore(aceitaDe)) {
            throw new IllegalArgumentException(
                    "a janela de dias precisa começar antes de terminar");
        }
        if ((horaDe == null) != (horaAte == null)) {
            throw new IllegalArgumentException(
                    "informe as duas horas ou nenhuma; uma só não descreve faixa nenhuma");
        }
        if (horaDe != null && !horaAte.isAfter(horaDe)) {
            throw new IllegalArgumentException("a faixa de hora precisa ter duração");
        }
    }

    /**
     * Se esta vaga serve para quem está esperando.
     *
     * <p>Compara em <strong>dia e hora de parede no fuso do salão</strong>, e não em instante: a
     * cliente disse "quinta à tarde", que é uma frase sobre o relógio da parede. Comparar
     * instantes faria a faixa escorregar uma hora na virada do horário de verão.
     */
    public boolean serve(UUID servicoDaVaga, UUID profissionalDaVaga, Instant inicioDaVaga,
                         ZoneId fuso) {
        if (!servicoId.equals(servicoDaVaga)) {
            return false;
        }
        // Espera sem profissional aceita qualquer um; com profissional, só ele.
        if (profissionalId != null && !profissionalId.equals(profissionalDaVaga)) {
            return false;
        }
        var quando = inicioDaVaga.atZone(fuso);
        LocalDate dia = quando.toLocalDate();
        if (dia.isBefore(aceitaDe) || dia.isAfter(aceitaAte)) {
            return false;
        }
        if (horaDe == null) {
            return true;
        }
        LocalTime hora = quando.toLocalTime();
        return !hora.isBefore(horaDe) && hora.isBefore(horaAte);
    }
}
