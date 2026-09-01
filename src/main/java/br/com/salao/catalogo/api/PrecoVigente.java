package br.com.salao.catalogo.api;

import br.com.salao.shared.dinheiro.Money;
import java.time.LocalDate;
import java.util.UUID;

/**
 * RT-CAT-002 — um preço e a data em que passou (ou passa) a valer.
 *
 * <p>Não existe data de fim: o preço vale até outro começar. Guardar o fim obrigaria a manter duas
 * linhas coerentes a cada alteração, e uma falha no meio disso deixaria dois preços vigentes ao
 * mesmo tempo — com a consulta dependendo de qual linha o banco devolvesse primeiro.
 *
 * @param vigenteDe pode estar no futuro: anunciar o aumento antes de aplicá-lo é o uso normal
 */
public record PrecoVigente(UUID id, Money valor, LocalDate vigenteDe) {

    public boolean valeEm(LocalDate data) {
        return !vigenteDe.isAfter(data);
    }

    public boolean aindaNaoComecou(LocalDate hoje) {
        return vigenteDe.isAfter(hoje);
    }
}
