# ADR-0004: Cache local Caffeine invalidado por LISTEN/NOTIFY

- **Status:** aceita · **Data:** 2026-08-28

## Contexto

A proposta inicial era Redis. Três premissas dela não se sustentam:

1. **Redis não é rápido por causa do snapshot.** RDB/AOF existem para durabilidade, porque muita
   gente usa Redis como banco. Para cache, persistência é irrelevante: se perder, recarrega do
   Postgres.
2. **Caffeine já é mais rápido que Redis** neste caso — mesmo processo, sem hop de rede, sem
   serialização. O trade-off nunca foi velocidade.
3. **O problema real do cache local é coerência.** Com três instâncias atrás de um balanceador,
   alterar o preço na A não invalida B e C. Sem resolver isso, cache local serve dado velho.

## Opções consideradas

| Opção | Prós | Contras |
|---|---|---|
| Redis | Coerência trivial | Contêiner novo comendo RAM da VM; hop de rede; mais um ponto de falha |
| Caffeine sem invalidação | Simples | Serve dado velho com mais de uma instância — inaceitável para preço |
| **Caffeine + `LISTEN/NOTIFY`** | Coerência com custo de infra zero; usa o Postgres que já existe | `NOTIFY` não é durável; exige listener saudável |

## Decisão

Caffeine local, invalidado por `LISTEN/NOTIFY` do Postgres. O `NOTIFY` é emitido **dentro da
transação** — o Postgres já entrega só no commit e descarta no rollback, o que é uma garantia
melhor do que emitir de um `@TransactionalEventListener(AFTER_COMMIT)`, que abre a janela
"commit passou, processo morreu, ninguém invalidou".

Listener em conexão dedicada, fora do pool do Hikari.

## Consequências

**Positivas.** Zero infra nova. Latência menor que Redis. Um componente a menos para operar,
monitorar e atualizar.

**Negativas, assumidas.** `LISTEN/NOTIFY` não é durável: se a conexão do listener cair,
invalidações somem em silêncio. Mitigação obrigatória — reconexão automática, **flush total do
cache ao reconectar**, métrica `cache.listener.up` com alerta, e `expireAfterWrite` de 30 minutos
como teto do estrago. Nunca aumentar esse TTL confiando no `NOTIFY`.

**Revisitar quando** (gatilhos objetivos, não "quando incomodar"):
- Mais de 3–4 instâncias, ou hit rate local abaixo de ~70%
- Necessidade de rate limit distribuído preciso
- Lock distribuído — até lá, `pg_advisory_lock` resolve
