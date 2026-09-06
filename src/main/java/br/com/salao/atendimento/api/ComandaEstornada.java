package br.com.salao.atendimento.api;

import br.com.salao.shared.evento.EventoDeDominio;
import java.time.Instant;
import java.util.UUID;

/**
 * EV-ATD-003 — o atendimento foi desfeito depois de pago.
 *
 * <p>Terminal: a comanda não volta de {@code ESTORNADA}. O que foi apurado sobre ela precisa ser
 * revertido — comissão (RT-FIN-003) e lançamento no razão (RT-FIN-007) —, e é este evento que dá o
 * gancho.
 *
 * <p>O motivo não vai no payload: é texto livre escrito por gente, e {@code event_publication} não
 * tem RLS (RN-INF-009). Ele fica na coluna, que é onde a tela da comanda o lê.
 */
public record ComandaEstornada(
        UUID estabelecimentoId,
        UUID comandaId,
        UUID clienteId,
        long numero,
        java.math.BigDecimal valorLiquido,
        Instant estornadaEm) implements EventoDeDominio {
}
