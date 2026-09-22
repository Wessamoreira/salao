package br.com.salao.agenda.internal.domain;

import br.com.salao.agenda.api.EsperaNaFila;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * RT-AGD-010 — quem da fila cabe numa vaga que abriu (RN-AGD-025), e em que ordem (RN-AGD-026).
 *
 * <h2>A disponibilidade responde, não o serviço</h2>
 *
 * <p>A espera não precisa ser do serviço que cancelou: a coloração de duas horas cancelada serve
 * para quem espera um corte. "Caber" também não é conta de minutos — os horários de cada serviço vêm
 * prontos da disponibilidade de RT-AGD-001, com jornada, bloqueio, pausa e recurso já descontados.
 * Esta classe só decide quais deles são <em>desta vaga</em> e <em>desta espera</em>.
 *
 * <h2>Começar dentro da vaga</h2>
 *
 * <p>Um horário que começa fora da vaga já estava livre antes do cancelamento: não é o tempo que
 * abriu. Terminar depois do fim da vaga é permitido — se o tempo seguinte estava livre, a
 * disponibilidade já disse que cabe.
 */
public final class CandidatasDaVaga {

    private CandidatasDaVaga() {
    }

    /** O tempo que abriu: de quem era, e de que hora a que hora. */
    public record Vaga(UUID profissionalId, Instant inicio, Instant fim) {
    }

    /**
     * Uma espera que cabe na vaga.
     *
     * @param posicao  a ordem de chegada entre as candidatas desta vaga, a partir de 1
     * @param horarios os inícios possíveis, em ordem — nunca vazio
     */
    public record Candidata(int posicao, EsperaNaFila espera, List<Instant> horarios) {

        public Candidata {
            horarios = List.copyOf(horarios);
        }
    }

    /**
     * As candidatas da vaga, na ordem de quem chegou primeiro.
     *
     * @param esperas            quem está na fila — o filtro por dia e profissional pode ter
     *                           vindo do banco, e é refeito aqui
     * @param horariosPorServico os inícios que a disponibilidade oferece para a profissional da
     *                           vaga, no dia da vaga, por serviço. Serviço ausente é serviço que ela
     *                           não faz
     */
    public static List<Candidata> de(Vaga vaga, Collection<EsperaNaFila> esperas,
                                     Map<UUID, List<Instant>> horariosPorServico, Instant agora,
                                     ZoneId fuso) {
        List<EsperaNaFila> emOrdem = esperas.stream()
                // Ordem de chegada. O id desempata para a ordem não depender de quem o banco
                // devolveu primeiro quando duas entraram no mesmo instante.
                .sorted(Comparator.comparing(EsperaNaFila::criadoEm)
                        .thenComparing(EsperaNaFila::id))
                .toList();

        var candidatas = new ArrayList<Candidata>();
        for (EsperaNaFila espera : emOrdem) {
            List<Instant> horarios = horariosPorServico
                    .getOrDefault(espera.servicoId(), List.of()).stream()
                    .filter(horario -> !horario.isBefore(vaga.inicio())
                            && horario.isBefore(vaga.fim()))
                    .filter(horario -> !horario.isBefore(agora))
                    // O serviço é o da própria espera: a pergunta aqui é profissional, dia e faixa.
                    .filter(horario -> espera.serve(espera.servicoId(), vaga.profissionalId(),
                            horario, fuso))
                    .sorted()
                    .distinct()
                    .toList();
            if (!horarios.isEmpty()) {
                candidatas.add(new Candidata(candidatas.size() + 1, espera, horarios));
            }
        }
        return List.copyOf(candidatas);
    }

    /**
     * A primeira candidata <strong>antes</strong> da escolhida que ainda não teve chamada nesta
     * vaga — quem seria pulada (RN-AGD-026).
     *
     * <p>"Não atendeu" conta como chamada: a recepção tentou, e seguir para a próxima é a ordem
     * funcionando, não um pulo.
     */
    public static Optional<Candidata> puladaAoChamar(List<Candidata> candidatas, UUID esperaId,
                                                     Collection<UUID> jaChamadas) {
        for (Candidata candidata : candidatas) {
            if (candidata.espera().id().equals(esperaId)) {
                return Optional.empty();
            }
            if (!jaChamadas.contains(candidata.espera().id())) {
                return Optional.of(candidata);
            }
        }
        return Optional.empty();
    }
}
