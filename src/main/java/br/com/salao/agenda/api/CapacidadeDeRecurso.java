package br.com.salao.agenda.api;

import java.util.List;

/**
 * RT-AGD-001 — quantas unidades um recurso tem e quais já estão tomadas.
 *
 * <p>Cada janela em {@code ocupacoes} é <strong>uma unidade ocupada</strong> naquele período, não
 * o recurso inteiro. Por isso a pergunta "cabe mais um?" é uma contagem de cruzamentos, e não um
 * teste de sobreposição: com três lavatórios, duas lavagens simultâneas ainda deixam um livre.
 *
 * @param quantidade unidades intercambiáveis, sempre ≥ 1 (RN-CAT-009)
 */
public record CapacidadeDeRecurso(int quantidade, List<Janela> ocupacoes) {

    public CapacidadeDeRecurso {
        if (quantidade < 1) {
            throw new IllegalArgumentException("recurso sem unidade não é recurso: " + quantidade);
        }
        ocupacoes = ocupacoes == null ? List.of() : List.copyOf(ocupacoes);
    }

    /** Se ainda sobra unidade livre durante toda a janela pedida. */
    public boolean cabeEm(Janela janela) {
        long tomadas = ocupacoes.stream().filter(janela::cruzaCom).count();
        return tomadas < quantidade;
    }
}
