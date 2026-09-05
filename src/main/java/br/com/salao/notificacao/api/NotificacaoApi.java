package br.com.salao.notificacao.api;

import br.com.salao.cliente.api.Finalidade;
import java.util.Map;
import java.util.UUID;

/**
 * RT-NOT-001 — contrato público do módulo {@code notificacao}.
 *
 * <p>É por aqui que {@code agenda} pede um lembrete e {@code atendimento} pede uma pesquisa.
 * Nenhum deles decide se pode enviar: essa pergunta é feita aqui, uma vez, contra o
 * {@code ClienteApi} — a regra da LGPD escrita duas vezes teria uma segunda cópia, e a segunda
 * cópia é a que continuaria mandando depois da revogação.
 */
public interface NotificacaoApi {

    /**
     * Põe a mensagem na fila. Ela sai depois, e pode não sair.
     *
     * @param referencia o fato único que gerou a mensagem — o id do agendamento, no caso do
     *                   lembrete. Pedir duas vezes com a mesma referência não manda duas
     *                   mensagens, e é isto que impede o job de varredura de mandar seis
     *                   lembretes do mesmo horário. Nulo para o que não tem fato único
     * @return o id da mensagem enfileirada, ou da que já existia
     */
    UUID enfileirar(UUID clienteId, Finalidade finalidade, CanalDeEnvio canal, String modelo,
                    Map<String, String> parametros, UUID referencia);
}
