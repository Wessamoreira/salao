package br.com.salao.catalogo.internal.application;

import br.com.salao.catalogo.api.TipoDeBloco;
import java.util.Set;
import java.util.UUID;

/**
 * RT-CAT-001 — uma etapa como veio de fora, ainda não validada.
 *
 * <p>Existe pelo mesmo motivo de {@code PeriodoBruto} em RT-EQP-002: o controller não constrói
 * objeto de domínio. {@code BlocoDoServico} e {@code SequenciaDeBlocos} recusam entrada inválida
 * com {@code IllegalArgumentException} — correto no domínio, e 500 na borda.
 *
 * <p>Foi a quinta ocorrência dessa família de defeito no projeto. Desta vez o tradutor entrou
 * antes de o defeito aparecer.
 *
 * <p>RT-CAT-003 acrescentou {@code recursosExigidos}, que chega cru pela mesma razão: são ids que
 * podem não existir, ou existir em outro salão.
 */
public record EtapaBruta(TipoDeBloco tipo, int duracaoMinutos, Set<UUID> recursosExigidos) {

    public EtapaBruta {
        recursosExigidos = recursosExigidos == null ? Set.of() : Set.copyOf(recursosExigidos);
    }

    /** Etapa que não disputa equipamento com ninguém — o caso comum. */
    public EtapaBruta(TipoDeBloco tipo, int duracaoMinutos) {
        this(tipo, duracaoMinutos, Set.of());
    }
}
