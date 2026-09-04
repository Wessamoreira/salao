package br.com.salao.agenda.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * RT-AGD-001 — contrato público da disponibilidade.
 *
 * <p>Responde a pergunta do balcão e do WhatsApp: <em>que horário eu posso oferecer para quem quer
 * este serviço neste dia?</em>
 */
public interface DisponibilidadeApi {

    /**
     * Os horários que podem ser oferecidos, em ordem.
     *
     * @param profissionalId quem deve atender, ou {@code null} para "qualquer um que faça". O
     *                       nulo é o caso comum de quem marca por telefone: a cliente quer o
     *                       horário, e só depois escolhe com quem
     */
    List<HorarioDisponivel> consultar(LocalDate data, UUID servicoId, UUID profissionalId);
}
