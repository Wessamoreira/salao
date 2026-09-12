package br.com.salao.conversacional.api;

import br.com.salao.shared.evento.EventoDeDominio;
import java.util.UUID;

/**
 * RT-CNV-001 — chegou mensagem nova, e ela é INÉDITA.
 *
 * <p>Publicado uma vez por mensagem de fato inserida. Reenvio da Meta não republica: o
 * {@code insert ... on conflict do nothing} devolve zero linhas e não há evento — é o que impede
 * o mesmo "marca quarta 10h" de virar dois agendamentos (RN-CNV-002).
 *
 * <h2>Só identificadores — RN-INF-009</h2>
 *
 * <p>Nem texto, nem telefone, nem nome. O payload do evento vive em {@code event_publication},
 * que é infraestrutura e <strong>não tem RLS</strong>: os eventos de todos os estabelecimentos
 * convivem na mesma tabela. O consumidor lê o conteúdo por {@code ConversacionalApi}, já dentro
 * do escopo do tenant.
 *
 * <p>O tipo vem no evento porque ele decide QUEM trata — áudio vai para o STT (RT-CNV-003), texto
 * vai direto para a orquestração (RT-CNV-004) — e porque não é dado pessoal.
 */
public record MensagemDeClienteRecebida(
        UUID estabelecimentoId,
        UUID mensagemId,
        TipoDeMensagem tipo) implements EventoDeDominio {
}
