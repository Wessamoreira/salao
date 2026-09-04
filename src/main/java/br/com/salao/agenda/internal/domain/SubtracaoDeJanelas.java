package br.com.salao.agenda.internal.domain;

import br.com.salao.agenda.api.Janela;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * RT-AGD-001 — o que sobra de um conjunto de janelas depois de tirar outro.
 *
 * <p>Gêmea de {@code SubtracaoDeIntervalos} do módulo {@code equipe}, que faz o mesmo com
 * {@code LocalTime}. A duplicação é deliberada e não vale a pena remover: aquela trabalha em hora
 * de parede, para "terça das 9h às 19h"; esta em instante, para "das 14h05 de 15/03 às 15h35". Um
 * tipo genérico que servisse às duas custaria mais em entendimento do que as trinta linhas.
 *
 * <p>Um trecho tomado no meio parte a janela em duas — que é o caso interessante: a profissional
 * livre das 9h às 18h com uma cliente das 13h às 14h tem duas janelas, não uma de nove horas.
 */
public final class SubtracaoDeJanelas {

    private SubtracaoDeJanelas() {
    }

    public static List<Janela> subtrair(List<Janela> originais, List<Janela> removidas) {
        if (removidas.isEmpty()) {
            return List.copyOf(originais);
        }
        List<Janela> ordenadas = removidas.stream()
                .sorted(Comparator.comparing(Janela::inicio))
                .toList();

        var resultado = new ArrayList<Janela>();
        for (Janela original : originais) {
            Instant cursor = original.inicio();
            for (Janela removida : ordenadas) {
                if (!removida.cruzaCom(original)) {
                    continue;
                }
                if (removida.inicio().isAfter(cursor)) {
                    resultado.add(new Janela(cursor, removida.inicio()));
                }
                if (removida.fim().isAfter(cursor)) {
                    cursor = removida.fim();
                }
            }
            if (cursor.isBefore(original.fim())) {
                resultado.add(new Janela(cursor, original.fim()));
            }
        }
        return List.copyOf(resultado);
    }
}
