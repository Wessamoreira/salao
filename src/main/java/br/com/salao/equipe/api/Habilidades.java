package br.com.salao.equipe.api;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * RT-EQP-004 — o que um profissional executa, e RT-CAT-004 — em quanto tempo.
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
 *
 * <h2>A duração só existe para serviço que a pessoa executa</h2>
 *
 * <p>{@code duracoes} guarda quanto <em>este</em> profissional leva num serviço, em minutos totais.
 * Toda chave precisa passar por {@link #executa}: dizer "a Ana leva 30 minutos numa coloração que
 * ela não faz" é uma contradição, e é justamente a que existiria se duração e habilidade fossem
 * tabelas separadas.
 *
 * <p>No regime aberto isso permite duração para qualquer serviço, que é o correto — ela executa
 * todos. No restrito, só para os da lista.
 */
public record Habilidades(boolean executaTodos, Set<UUID> servicos, Map<UUID, Integer> duracoes) {

    public Habilidades {
        servicos = servicos == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(servicos));
        duracoes = duracoes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(duracoes));

        if (!executaTodos && servicos.isEmpty()) {
            throw new IllegalArgumentException(
                    "restringir a nenhum serviço deixaria o profissional sem poder atender nada; "
                            + "para tirá-lo da agenda, desative-o");
        }
        var forasteiras = new TreeSet<String>();
        for (Map.Entry<UUID, Integer> entrada : duracoes.entrySet()) {
            if (!executaTodos && !servicos.contains(entrada.getKey())) {
                forasteiras.add(entrada.getKey().toString());
            }
            if (entrada.getValue() == null || entrada.getValue() <= 0) {
                throw new IllegalArgumentException(
                        "duração precisa ser positiva no serviço " + entrada.getKey()
                                + "; para usar a do catálogo, não informe nenhuma");
            }
        }
        if (!forasteiras.isEmpty()) {
            throw new IllegalArgumentException(
                    "há duração para serviço que o profissional não executa: "
                            + String.join(", ", forasteiras));
        }
    }

    public Habilidades(boolean executaTodos, Set<UUID> servicos) {
        this(executaTodos, servicos, Map.of());
    }

    /** O padrão de quem acabou de ser cadastrado: o cardápio inteiro, no tempo do catálogo. */
    public static Habilidades todosOsServicos() {
        return new Habilidades(true, Set.of(), Map.of());
    }

    public static Habilidades apenas(Set<UUID> servicos) {
        return new Habilidades(false, servicos, Map.of());
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

    /**
     * RT-CAT-004 — quanto esta pessoa leva neste serviço, se for diferente do catálogo.
     *
     * <p>Vazio significa "usa a do catálogo", que é o caso da maioria — e é diferente de zero, que
     * não é duração nenhuma.
     */
    public Optional<Integer> duracaoDe(UUID servicoId) {
        return Optional.ofNullable(duracoes.get(servicoId));
    }
}
