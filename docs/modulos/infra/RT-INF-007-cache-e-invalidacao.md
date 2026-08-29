---
id: RT-INF-007
titulo: Cache local com invalidação por LISTEN/NOTIFY
modulo: infra
fase: 0
perfil: completo
status: implementado
depende_de: [RT-INF-002]
permissoes: []
eventos: []
regras: [RN-INF-010]
atualizado_em: 2026-08-29
---

# RT-INF-007 — Cache local com invalidação por LISTEN/NOTIFY

## 1. Objetivo

Servir catálogo, jornada e configuração da memória, sem que instâncias diferentes sirvam versões
diferentes do mesmo dado.

## 2. Contexto

O problema do cache local nunca foi velocidade — Caffeine já é mais rápido que Redis por estar no
mesmo processo, sem hop de rede e sem serialização. O problema é **coerência**: com três instâncias
atrás de um balanceador, alterar o preço na A não invalida B e C, e as duas seguem cobrando o
valor antigo.

Resolver isso com Redis custaria um contêiner novo comendo RAM de uma VM pequena. O Postgres já
está lá e já sabe fazer isso ([ADR-0004](../../adr/0004-cache-caffeine-notify.md)).

## 3. RN-INF-010 — toda chave de cache começa pelo tenant

**É a peça que impede o cache de desfazer a RLS.** Uma entrada guardada só por `servicoId` seria
servida a qualquer estabelecimento que pedisse o mesmo id — e o banco **nem chegaria a ser
consultado**, então nem a política nem a checagem de posse teriam chance de agir. O isolamento
cairia exatamente onde ninguém está olhando.

Duas camadas:

| Camada | Papel |
|---|---|
| `GeradorDeChaveComTenant` | **Gerador padrão.** Um `@Cacheable` escrito sem pensar em multi-tenant já nasce isolado. Pedir que cada anotação declare o tenant em SpEL funciona até a primeira vez que alguém esquece — e o esquecimento não dá erro, dá resposta de outro estabelecimento |
| `@chaveDeCache.de(#id)` | Para cache com **invalidação explícita**. O gerador padrão produz `tenant\|metodo(args)`, que o invalidador não teria como reconstruir a partir da chave de negócio |

## 4. O `NOTIFY` vai dentro da transação

O Postgres enfileira o `NOTIFY` e só o entrega no commit — e o descarta no rollback. É exatamente
a semântica desejada, e vem de graça.

A alternativa aparentemente mais correta — emitir de um `@TransactionalEventListener(AFTER_COMMIT)`
— é **pior**: entre o commit e o envio existe uma janela em que o processo pode morrer, e aí o
preço novo está no banco enquanto as outras instâncias seguem servindo o antigo. Sem erro, sem
alerta, até o TTL vencer.

`CacheIT.invalidacao_segue_a_transacao` prova as duas metades: rollback não propaga, commit
propaga. Ele também prova, de lado, que o `JdbcClient` usa a conexão da transação em curso e não
uma nova do pool — se fosse outra, o `NOTIFY` escaparia mesmo com rollback.

## 5. O ouvinte, e o que fazer com uma entrega não durável

**Conexão dedicada, fora do pool.** Uma conexão presa em `LISTEN` é uma conexão a menos no Hikari
*para sempre*. Numa VM pequena isso é caminho direto para exaustão, e o sintoma apareceria como
lentidão em requisições que nada têm a ver com cache.

**`LISTEN/NOTIFY` não é durável.** Se a conexão cair, as invalidações do intervalo **somem** — não
há fila nem reentrega. A instância seguiria servindo preço velho sem nenhum sinal.

Três respostas, e as três são necessárias:

| Resposta | Papel |
|---|---|
| **Reconexão limpa o cache inteiro** | Deliberadamente grosseiro. Não há como saber o que se perdeu, então nada em memória é confiável. O custo é recarregar do Postgres |
| `expireAfterWrite` de 30 min | **Não é ajuste de performance: é o teto do estrago.** No pior caso, meia hora de dado velho |
| Métrica `cache.listener.up` | Torna a queda visível. Sem ela, o ouvinte cair não gera exceção, nem 5xx, nem alerta |

> **Não aumente o TTL confiando no `NOTIFY`.** Ele é a otimização; o TTL é a garantia.

A conexão se identifica como `salao-ouvinte-cache` em `pg_stat_activity` — é como se descobre o
que ela é ao investigar conexões ociosas, e é como o teste de reconexão a encontra para derrubá-la.

**Payload ilegível é descartado sem derrubar o ouvinte.** Perder uma invalidação é ruim; perder
todas as seguintes é muito pior.

## 6. Correção sobre o plano original

O plano previa `refreshAfterWrite` de 5 minutos ("serve o velho enquanto recarrega"). **Retirado:**
ele exige um `CacheLoader`, que o modelo de `@Cacheable` não tem — o Caffeine falha na construção.
Só faria sentido com cache de leitura programática, e não é o caso aqui.

## 7. O que cacheia

| Cacheia | Não cacheia |
|---|---|
| Catálogo de serviços e preços | Agenda — escrita constante, correção crítica |
| Jornada e configuração do estabelecimento | Saldo financeiro |
| `capabilities` do usuário | Saldo de estoque |
| Relatório agregado de período fechado | Comanda aberta |

Para a agenda o ganho vem de índice certo, projeção e keyset — não de cache.

## 8. Testes

- [x] `chave_inclui_o_tenant` — o mesmo id em outro estabelecimento não reaproveita
- [x] `notificacao_externa_invalida`
- [x] `invalidacao_segue_a_transacao` — rollback não propaga, commit propaga
- [x] `reconexao_esvazia_o_cache` — derruba o backend com `pg_terminate_backend` e verifica que o
      ouvinte reconecta sozinho e esvazia
- [x] `payload_ilegivel_nao_derruba_o_ouvinte`

## 9. O que a implementação revelou

**O driver do PostgreSQL saiu do escopo `runtime`.** `LISTEN/NOTIFY` não é padrão JDBC: usar
`org.postgresql.PGConnection` exige o driver em tempo de compilação. O acoplamento é consciente e
já estava dado — a exclusion constraint da agenda e a RLS também são específicas do Postgres.

## 10. Pendências

- [ ] Warm-up no `ApplicationReadyEvent` (catálogo + agenda do dia): só faz sentido quando existir
      catálogo. Entra com `RT-CAT-001`
- [ ] `ETag`/`If-None-Match` nos GETs do painel do balcão — entra com `RT-AGD-012`
- [ ] Alerta de `cache.listener.up == 0` por mais de 1 min (entra em RT-INF-008)
- [ ] Hit rate do Caffeine exposto no Micrometer: `recordStats()` está ligado, falta registrar o
      `CacheMetricsRegistrar` para os caches criados dinamicamente
