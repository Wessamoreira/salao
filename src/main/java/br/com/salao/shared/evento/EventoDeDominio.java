package br.com.salao.shared.evento;

import java.util.UUID;

/**
 * RT-INF-006 / RN-INF-009 — contrato de todo evento consumido de forma assíncrona.
 *
 * <h2>Por que o evento carrega o tenant</h2>
 *
 * <p>Um {@code @ApplicationModuleListener} roda depois do commit, em outra thread, e abre
 * transação <strong>antes</strong> de o corpo do método executar. Se o tenant não estiver no
 * escopo nesse instante, {@code TenantAwareTransactionManager} recusa a transação — e não há
 * onde o listener possa abri-lo, porque ele já perdeu a corrida.
 *
 * <p>No caminho normal quem resolve é o {@link PropagadorDeTenant}, que leva o escopo da thread
 * que publicou para a que consome. Mas ele não cobre o reenvio de pendências: ali não existe
 * thread de origem, só uma linha em {@code event_publication}. É desse payload que o tenant é
 * recuperado — e por isso ele precisa estar dentro do evento.
 *
 * <h2>RN-INF-009 — evento carrega ID, nunca PII</h2>
 *
 * <p>O payload serializado vive em {@code event_publication}, que é infraestrutura e
 * <strong>não tem RLS</strong>: ali os eventos de todos os estabelecimentos convivem na mesma
 * tabela. Nome de cliente, telefone ou ficha de química nesse payload seria dado pessoal — parte
 * dele sensível — fora do perímetro que a RLS garante. O consumidor busca o que precisa pela API
 * do módulo dono do dado.
 */
public interface EventoDeDominio {

    /** O estabelecimento a que o evento pertence. Nunca nulo. */
    UUID estabelecimentoId();
}
