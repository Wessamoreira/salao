package br.com.salao.equipe.internal.application;

import java.time.LocalTime;

/**
 * RT-EQP-002 — um par de horários como veio de fora, ainda não validado.
 *
 * <p>Existe para que o <strong>controller não construa objeto de domínio</strong>. O construtor de
 * {@code IntervaloDeTrabalho} recusa intervalo invertido com {@code IllegalArgumentException} —
 * correto para o domínio, e desastroso na borda: sem tradução, ela cai no tratador genérico e
 * vira 500, dizendo "algo quebrou aqui dentro" sobre um horário que a pessoa digitou trocado.
 *
 * <p>É a quarta vez que essa família de defeito aparece no projeto (rota inexistente, parâmetro
 * malformado, permissão negada). O padrão já é conhecido: <em>exceção que a borda pode provocar
 * precisa de tradutor</em>.
 */
public record PeriodoBruto(LocalTime inicio, LocalTime fim) {
}
