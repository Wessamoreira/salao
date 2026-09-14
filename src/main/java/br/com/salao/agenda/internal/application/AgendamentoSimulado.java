package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.api.BlocoDoAgendamento;
import br.com.salao.agenda.api.Janela;
import br.com.salao.agenda.api.StatusAgendamento;
import java.util.List;
import java.util.UUID;

/**
 * RT-CNV-005 — o que {@code CriarAgendamentoUseCase.executar} gravaria, sem ter gravado.
 *
 * <p>Sem id e sem versão: não existe. {@code status} é o que ele teria ao nascer (RN-AGD-013), e
 * {@code blocos} já trazem as unidades de recurso que a gravação escolheria agora.
 *
 * <p>{@code foraDaJornada} (RT-CNV-006): a gravação sem forçamento recusaria. A simulação informa em
 * vez de recusar, para quem simula decidir — o agente pede o aceite de quem vai atender.
 */
public record AgendamentoSimulado(
        UUID clienteId,
        UUID profissionalId,
        UUID servicoId,
        String servico,
        StatusAgendamento status,
        Janela periodo,
        List<BlocoDoAgendamento> blocos,
        boolean foraDaJornada) {

    public AgendamentoSimulado {
        blocos = List.copyOf(blocos);
    }
}
