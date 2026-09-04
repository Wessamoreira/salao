package br.com.salao.equipe.api;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * RT-EQP-004 — o que um profissional executa.
 *
 * <h2>Os dois regimes, e por que não há um padrão implícito</h2>
 *
 * <p>Lista vazia significaria "faz tudo" ou "não faz nada"? As duas respostas erram, cada uma do
 * seu jeito:
 *
 * <ul>
 *   <li><strong>Vazia = não faz nada</strong> obrigaria um salão de 6 profissionais e 40 serviços
 *       a marcar 240 caixas antes do primeiro agendamento — e faria todo serviço novo nascer
 *       invisível para todo mundo, em silêncio, até alguém revisitar 6 cadastros.</li>
 *   <li><strong>Vazia = faz tudo</strong> deixa a recepção marcar progressiva com quem só faz
 *       manicure, e a descoberta é no balcão, com a cliente sentada.</li>
 * </ul>
 *
 * <p>Então o regime é dito, não deduzido: {@code executaTodos} escolhe entre os dois. O padrão é
 * {@code true} porque é o que faz um salão novo funcionar no primeiro dia; restringir é um ato
 * deliberado.
 *
 * <h2>Restringir para lista vazia é recusado</h2>
 *
 * <p>Seria o único jeito de deixar alguém sem poder atender nada sem dizer isso em lugar nenhum —
 * a agenda simplesmente pararia de oferecer horário dela. Quem quer tirar a pessoa da agenda
 * desativa o profissional, que é explícito e reversível.
 */
public record Habilidades(boolean executaTodos, Set<UUID> servicos) {

    public Habilidades {
        servicos = servicos == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(servicos));
        if (!executaTodos && servicos.isEmpty()) {
            throw new IllegalArgumentException(
                    "restringir a nenhum serviço deixaria o profissional sem poder atender nada; "
                            + "para tirá-lo da agenda, desative-o");
        }
    }

    /** O padrão de quem acabou de ser cadastrado: o cardápio inteiro. */
    public static Habilidades todosOsServicos() {
        return new Habilidades(true, Set.of());
    }

    public static Habilidades apenas(Set<UUID> servicos) {
        return new Habilidades(false, servicos);
    }

    /**
     * Se este profissional executa o serviço.
     *
     * <p>Note que no regime {@code executaTodos} a lista é ignorada — e não consultada e vazia.
     * São coisas diferentes: a segunda daria "não executa nada", que é o defeito que a chave
     * existe para evitar.
     */
    public boolean executa(UUID servicoId) {
        return executaTodos || servicos.contains(servicoId);
    }
}
