package br.com.salao.catalogo.internal.application;

import br.com.salao.catalogo.api.TipoDeBloco;

/**
 * RT-CAT-001 — uma etapa como veio de fora, ainda não validada.
 *
 * <p>Existe pelo mesmo motivo de {@code PeriodoBruto} em RT-EQP-002: o controller não constrói
 * objeto de domínio. {@code BlocoDoServico} e {@code SequenciaDeBlocos} recusam entrada inválida
 * com {@code IllegalArgumentException} — correto no domínio, e 500 na borda.
 *
 * <p>Foi a quinta ocorrência dessa família de defeito no projeto. Desta vez o tradutor entrou
 * antes de o defeito aparecer.
 */
public record EtapaBruta(TipoDeBloco tipo, int duracaoMinutos) {
}
