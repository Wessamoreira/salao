package br.com.salao.atendimento.api;

import br.com.salao.shared.evento.EventoDeDominio;
import java.time.Instant;
import java.util.UUID;

/**
 * EV-ATD-002 — a comanda voltou a aceitar alteração, e o que foi apurado sobre ela não vale mais.
 *
 * <p>Existe por uma razão concreta, e não por simetria: {@code ComandaFechada} vai ser consumido
 * por RT-FIN-003 para apurar comissão. Reabrir e fechar de novo publicaria o fechamento uma segunda
 * vez — e sem este evento no meio, a apuração contaria em dobro sem nada avisar.
 *
 * <p>Nenhum consumidor existe ainda. Publicá-lo agora é o que torna a compensação possível quando
 * existir; a alternativa seria uma armadilha silenciosa esperando a próxima rotina.
 */
public record ComandaReaberta(
        UUID estabelecimentoId,
        UUID comandaId,
        long numero,
        Instant reabertaEm) implements EventoDeDominio {
}
