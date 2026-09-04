package br.com.salao.agenda.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * RT-AGD-002 — o agendamento como os outros módulos e a tela o veem.
 *
 * <p><strong>Sem o nome da cliente</strong>, e de propósito. {@code ClienteApi.existe} foi escrito
 * para este caso — "agendar precisa validar o vínculo sem carregar dado pessoal que não vai usar, e
 * o que não é carregado não vaza em log". Embutir o nome aqui obrigaria toda leitura de agendamento
 * a carregar dado pessoal, inclusive as que só querem saber se o horário está ocupado.
 *
 * <p>Quem precisa exibir busca pela {@code ClienteApi} — é o que o controller faz para a resposta
 * da criação, uma vez, para quem acabou de digitar aquele nome.
 */
public record AgendamentoResumo(
        UUID id,
        UUID clienteId,
        UUID profissionalId,
        UUID servicoId,
        StatusAgendamento status,
        Instant inicio,
        Instant fim,
        OrigemDoAgendamento origem,
        String observacao,
        List<BlocoDoAgendamento> blocos,
        int versao) {

    /** Quanto do profissional este agendamento consome — menos que a duração, se houver pausa. */
    public int minutosDoProfissional() {
        return blocos.stream()
                .filter(bloco -> bloco.profissionalId() != null)
                .mapToInt(bloco -> (int) bloco.janela().duracao().toMinutes())
                .sum();
    }
}
