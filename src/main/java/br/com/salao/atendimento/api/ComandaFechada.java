package br.com.salao.atendimento.api;

import br.com.salao.shared.evento.EventoDeDominio;
import java.time.Instant;
import java.util.UUID;

/**
 * EV-ATD-001 — o atendimento acabou, foi pago, e os números pararam de mudar.
 *
 * <p>É o evento que o {@code _modulo.md} da agenda já declarava consumir, para mover o agendamento
 * para {@code CONCLUIDO}. E é o que RT-FIN-003 (apurar comissão) e RT-FIN-008 (comprovante) vão
 * ouvir.
 *
 * <p>Carrega os totais porque quem os quer — apuração e comprovante — precisaria buscá-los de
 * volta; e eles são <strong>imutáveis a partir daqui</strong>, então o payload não corre risco de
 * envelhecer. Já o cliente vem como id: nome é dado pessoal, e {@code event_publication} não tem
 * RLS (RN-INF-009).
 */
public record ComandaFechada(
        UUID estabelecimentoId,
        UUID comandaId,
        UUID clienteId,
        long numero,
        java.math.BigDecimal valorLiquido,
        Instant fechadaEm) implements EventoDeDominio {
}
