package br.com.salao.agenda.internal.domain;

import br.com.salao.agenda.api.Janela;
import br.com.salao.catalogo.api.BlocoDoServico;
import br.com.salao.catalogo.api.SequenciaDeBlocos;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * RN-AGD-003 — a receita vira linha do tempo.
 *
 * <p>Domínio puro. Recebe a receita (já com a duração deste profissional, RN-CAT-013) e o instante
 * de início, e devolve onde cada bloco cai. Não sabe de banco, de recurso alocado nem de tenant.
 *
 * <p>Os blocos são <strong>encostados</strong>: o fim de um é o início do seguinte. Deixar folga
 * entre eles seria inventar tempo que o salão não cobra e não usa — e como a janela é
 * {@code [inicio, fim)} (RN-AGD-010), encostar não é sobrepor.
 */
public final class MaterializadorDeBlocos {

    private MaterializadorDeBlocos() {
    }

    /**
     * Um bloco no tempo, antes de saber qual unidade de recurso vai usar.
     *
     * @param profissionalOcupado se este bloco toma o profissional. Falso na pausa — e é essa
     *                            linha que faz a agenda render
     */
    public record BlocoNoTempo(int ordem, br.com.salao.catalogo.api.TipoDeBloco tipo,
                               Janela janela, boolean profissionalOcupado,
                               Set<UUID> recursosExigidos) {
    }

    public static List<BlocoNoTempo> materializar(SequenciaDeBlocos receita, Instant inicio) {
        var blocos = new ArrayList<BlocoNoTempo>(receita.blocos().size());
        Instant cursor = inicio;
        int ordem = 0;
        for (BlocoDoServico bloco : receita.blocos()) {
            Janela janela = Janela.de(cursor, Duration.ofMinutes(bloco.duracaoMinutos()));
            blocos.add(new BlocoNoTempo(ordem++, bloco.tipo(), janela,
                    bloco.tipo().ocupaOProfissional(), bloco.recursosExigidos()));
            cursor = janela.fim();
        }
        return List.copyOf(blocos);
    }

    /** Do primeiro instante ao último — o que a cliente vive, e o que a cabeça guarda. */
    public static Janela periodoTotal(List<BlocoNoTempo> blocos) {
        return new Janela(blocos.getFirst().janela().inicio(), blocos.getLast().janela().fim());
    }
}
