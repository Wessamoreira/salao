package br.com.salao.iam.api;

import java.util.UUID;

/**
 * RT-ATD-004 — os tetos que dependem de quem está agindo.
 *
 * <p>Nasceu quando {@code atendimento} precisou recusar um desconto grande demais. Ele não pergunta
 * "qual é o perfil desta pessoa" — isso o faria repetir, do lado de fora, o mapa que só o
 * {@code iam} conhece, e as duas cópias divergiriam na primeira mudança. Ele pergunta o
 * <strong>teto</strong>, que é o que precisa saber.
 */
public interface LimitesDoUsuarioApi {

    /**
     * Quanto este usuário pode descontar, em pontos percentuais ({@code 10} é 10%).
     *
     * <p>Zero para usuário desconhecido — e isso inclui job agendado e consumidor de evento, que
     * agem sem ninguém logado. Falha fechada: quem não tem rosto não dá desconto.
     */
    int descontoMaximoPercentual(UUID usuarioId);
}
