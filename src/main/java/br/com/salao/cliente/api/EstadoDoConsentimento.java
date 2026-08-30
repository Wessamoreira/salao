package br.com.salao.cliente.api;

import java.time.Instant;

/**
 * RT-CLI-004 — o que vale hoje para uma finalidade.
 *
 * <p>{@code SEM_REGISTRO} é um estado próprio, e não um {@code concedido = false}: as duas coisas
 * levam a resultados diferentes conforme a base legal, e confundi-las faria o lembrete de horário
 * parar de sair para toda cliente que nunca foi perguntada — que são todas, no primeiro dia.
 *
 * @param desde  quando a decisão vigente foi registrada, ou {@code null} se nunca houve
 * @param canal  onde ela foi coletada, ou {@code null} se nunca houve
 */
public record EstadoDoConsentimento(
        Finalidade finalidade,
        BaseLegal baseLegal,
        Decisao decisao,
        boolean podeReceber,
        Instant desde,
        CanalDeConsentimento canal) {

    public enum Decisao {
        CONCEDIDO,
        RECUSADO,
        SEM_REGISTRO
    }

    /** O estado de quem nunca se manifestou: o padrão da base legal decide. */
    public static EstadoDoConsentimento semRegistro(Finalidade finalidade) {
        return new EstadoDoConsentimento(finalidade, finalidade.baseLegal(),
                Decisao.SEM_REGISTRO, finalidade.permitidoSemRegistro(), null, null);
    }

    public static EstadoDoConsentimento registrado(Finalidade finalidade, boolean concedido,
                                                   Instant desde, CanalDeConsentimento canal) {
        return new EstadoDoConsentimento(finalidade, finalidade.baseLegal(),
                concedido ? Decisao.CONCEDIDO : Decisao.RECUSADO, concedido, desde, canal);
    }
}
