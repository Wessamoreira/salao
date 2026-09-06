package br.com.salao.agenda.api;

import java.util.Optional;
import java.util.UUID;

/**
 * RT-ATD-001 — o agendamento visto de fora do módulo.
 *
 * <p>Nasceu quando {@code atendimento} precisou abrir comanda a partir de um agendamento: ele
 * precisa saber de quem é o horário e se ele ainda vale. Sem este contrato, a saída seria um
 * {@code join} entre módulos — que é proibido, e é justamente o que torna a extração futura cara.
 *
 * <p>Devolve o {@link AgendamentoResumo} inteiro, e não só o {@code clienteId} que a abertura usa:
 * o resumo é o que o módulo já expõe às telas, e recortar um DTO por chamador multiplicaria
 * contratos que dizem a mesma coisa. Ele não carrega dado pessoal — nem o nome da cliente está lá.
 */
public interface AgendaApi {

    /** O agendamento, se existir no salão atual. */
    Optional<AgendamentoResumo> porId(UUID agendamentoId);
}
