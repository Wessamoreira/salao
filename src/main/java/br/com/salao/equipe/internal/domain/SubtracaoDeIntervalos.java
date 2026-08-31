package br.com.salao.equipe.internal.domain;

import br.com.salao.equipe.api.IntervaloDeTrabalho;
import java.util.ArrayList;
import java.util.List;

/**
 * RT-EQP-003 — o que sobra da jornada depois dos bloqueios.
 *
 * <h2>É aqui que a disponibilidade nasce</h2>
 *
 * <p>A jornada diz "terça das 9h às 19h". O curso diz "dia 14, das 14h às 16h". A resposta que a
 * agenda precisa é "9h-14h e 16h-19h" — e produzi-la é subtração de intervalos, não um
 * {@code if}.
 *
 * <p>Domínio puro, sem banco: é a parte com mais casos de borda da rotina e a que mais barato sai
 * testar sozinha. Um bloqueio pode cortar o começo, o fim, o meio (partindo um intervalo em dois),
 * o intervalo inteiro, ou nada.
 */
public final class SubtracaoDeIntervalos {

    private SubtracaoDeIntervalos() {
    }

    /**
     * Remove {@code removidos} de {@code base}, devolvendo o que sobra em ordem.
     *
     * <p>Os removidos podem se sobrepor entre si — férias que englobam um feriado é banal
     * (ver V19) —, e por isso a subtração é aplicada em sequência, cada uma sobre o resultado da
     * anterior. Tentar unir os removidos antes seria mais código para o mesmo resultado.
     */
    public static List<IntervaloDeTrabalho> subtrair(List<IntervaloDeTrabalho> base,
                                                     List<IntervaloDeTrabalho> removidos) {
        List<IntervaloDeTrabalho> resultado = new ArrayList<>(base);
        for (IntervaloDeTrabalho removido : removidos) {
            List<IntervaloDeTrabalho> proximo = new ArrayList<>();
            for (IntervaloDeTrabalho atual : resultado) {
                proximo.addAll(subtrairUm(atual, removido));
            }
            resultado = proximo;
        }
        resultado.sort((a, b) -> a.inicio().compareTo(b.inicio()));
        return List.copyOf(resultado);
    }

    /** Zero, um ou <strong>dois</strong> pedaços: o corte no meio parte o intervalo. */
    private static List<IntervaloDeTrabalho> subtrairUm(IntervaloDeTrabalho atual,
                                                        IntervaloDeTrabalho removido) {
        if (!atual.sobrepoe(removido)) {
            return List.of(atual);
        }
        var pedacos = new ArrayList<IntervaloDeTrabalho>(2);
        if (atual.inicio().isBefore(removido.inicio())) {
            pedacos.add(new IntervaloDeTrabalho(atual.inicio(), removido.inicio()));
        }
        if (removido.fim().isBefore(atual.fim())) {
            pedacos.add(new IntervaloDeTrabalho(removido.fim(), atual.fim()));
        }
        return pedacos;
    }
}
