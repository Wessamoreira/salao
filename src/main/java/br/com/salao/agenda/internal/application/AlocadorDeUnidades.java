package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.api.Janela;
import br.com.salao.agenda.internal.infra.AgendaJdbc.UnidadeOcupada;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * RT-AGD-002 — qual das unidades do recurso este bloco vai usar.
 *
 * <h2>Por que alocar uma unidade, e não contar</h2>
 *
 * <p>{@code recurso} é contável — "Lavatório, quantidade 3" (RN-CAT-009) — e {@code EXCLUDE}
 * garante <em>no máximo um</em>, não <em>no máximo três</em>. Contar na aplicação ("dois ocupados,
 * ainda cabe") é verificação sob concorrência sem trava: duas marcações simultâneas passam pela
 * mesma contagem e estouram a capacidade.
 *
 * <p>Escolhendo <strong>qual</strong> unidade, a constraint sobre
 * {@code (recurso_id, unidade, periodo)} volta a garantir a capacidade no banco — com o mesmo
 * mecanismo que protege a jornada, e sem o salão ter de nomear lavatório nenhum. Era a maior
 * dívida que RT-CAT-003 deixou registrada.
 *
 * <h2>O que a escolha gulosa custa</h2>
 *
 * <p>A primeira unidade livre, na ordem. Em grafo de intervalos, alocar por ordem de <em>início</em>
 * é ótimo — mas as marcações não chegam nessa ordem, e uma marcação posterior que comece antes
 * pode ser recusada mesmo cabendo com um rearranjo. Com dois ou três lavatórios isso pode nunca
 * aparecer; está registrado como pendência, para ser medido antes de complicar.
 */
final class AlocadorDeUnidades {

    private AlocadorDeUnidades() {
    }

    /**
     * A primeira unidade livre de {@code recursoId} durante {@code janela}.
     *
     * @param jaAlocadas o que este mesmo agendamento já tomou — sem isso, dois blocos do mesmo
     *                   serviço receberiam a mesma unidade e o banco recusaria a própria escrita
     */
    static Optional<Integer> primeiraLivre(UUID recursoId, int quantidade, Janela janela,
                                           Map<UUID, List<UnidadeOcupada>> ocupadas,
                                           List<UnidadeOcupada> jaAlocadas) {
        var tomadas = new ArrayList<>(ocupadas.getOrDefault(recursoId, List.of()));
        tomadas.addAll(jaAlocadas);

        for (int unidade = 1; unidade <= quantidade; unidade++) {
            final int candidata = unidade;
            boolean livre = tomadas.stream().noneMatch(ocupada ->
                    ocupada.unidade() == candidata && ocupada.janela().cruzaCom(janela));
            if (livre) {
                return Optional.of(candidata);
            }
        }
        return Optional.empty();
    }
}
