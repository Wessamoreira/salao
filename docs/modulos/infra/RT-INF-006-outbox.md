---
id: RT-INF-006
titulo: Outbox transacional
modulo: infra
fase: 0
perfil: completo
status: implementado
depende_de: [RT-INF-002, RT-INF-005]
permissoes: []
eventos: []
regras: [RN-INF-009]
atualizado_em: 2026-08-29
---

# RT-INF-006 — Outbox transacional

## 1. Objetivo

Garantir que um efeito externo — WhatsApp, e-mail, integração — só aconteça se a transação que o
originou tiver commitado, e que aconteça **ao menos uma vez** mesmo com falha ou reinício.

## 2. Contexto de negócio

Sem outbox, publicar direto com `@Async` produz os dois erros opostos, e ambos aparecem em
produção:

- O evento sai e o commit falha depois → **o cliente recebe confirmação de um agendamento que não
  existe.** Ele aparece no salão no dia marcado.
- O commit passa e o envio falha → a confirmação nunca chega, e ninguém fica sabendo, porque
  falha de envio assíncrono não vira 5xx nem erro no Sentry.

O primeiro é constrangedor; o segundo é invisível, que é pior.

## 3. Como funciona

```
@Transactional: grava o agendamento + INSERT em event_publication   ← mesmo commit
Depois do commit: listener assíncrono consome
Sucesso: a publicação vai para event_publication_archive
Falha:   a publicação fica pendente, e o ReenviadorDeEventos a reentrega
```

Entrega é **ao menos uma vez**. Consumidor precisa ser idempotente — não é recomendação, é o
contrato.

## 4. A decisão que custou mais: o tenant precisa atravessar a thread

`@ApplicationModuleListener` é `@Async` + `@Transactional`: a transação abre **antes** do corpo do
método. Como `TenantAwareTransactionManager` exige o tenant nesse instante, e `ScopedValue` não
atravessa threads, o listener falharia antes de ter chance de abrir o escopo. Não existe "abrir o
tenant na primeira linha do método" — quando o método começa, a transação já foi recusada.

**Descartado: `@Aspect`.** Teria de rodar depois do despacho do `@Async` e antes do
`@Transactional` — e as duas advices usam `LOWEST_PRECEDENCE`, então a ordem entre elas não é algo
em que se possa confiar. Funcionaria hoje e quebraria num upgrade do Boot, em silêncio.

**Adotado: `TaskDecorator`.** Envolve o `Runnable` na submissão e o desembrulha já na thread
trabalhadora. Sem disputa de ordem e sem depender de detalhe interno do Spring.

Ele não cobre o reenvio de pendências, onde não existe thread de origem — só uma linha na tabela.
Daí o segundo mecanismo: **o evento carrega o tenant** ({@code EventoDeDominio}), e o reenviador
o recupera do payload.

## 5. RN-INF-009 — evento carrega ID, nunca PII

`event_publication` é infraestrutura: **não tem `estabelecimento_id` nem RLS**, e os eventos de
todos os estabelecimentos convivem ali. Foi decisão consciente — a estrutura é do Modulith, que
nunca preencheria uma coluna de tenant, e o reenvio precisa atravessar estabelecimentos, o que sob
RLS seria impossível.

O preço é este: **nome de cliente, telefone ou ficha de química dentro de um evento seria dado
pessoal fora do perímetro da RLS**, parte dele sensível pela LGPD. O consumidor busca o que
precisa pela API do módulo dono do dado.

`SchemaIT` lista as duas tabelas como infraestrutura, com essa justificativa escrita.

## 6. Reenvio por estabelecimento

`republish-outstanding-events-on-restart` do Modulith está **desligado**: ele roda no startup sem
tenant no escopo, e aqui toda transação exige um. Além disso ele só cobre reinício de processo —
um listener que falhou com o processo de pé ficaria pendente para sempre.

`ReenviadorDeEventos` percorre estabelecimento por estabelecimento (lista obtida pela conexão de
manutenção, ADR-0010), abre o escopo de cada um e reenvia só as publicações daquele tenant.

**Idade mínima de 5 minutos**: publicação recente pode estar sendo processada neste instante, e
reenviar seria provocar duplicata de propósito. O consumidor é idempotente de qualquer forma, mas
não há motivo para criar trabalho para essa idempotência.

**Falha de um estabelecimento não interrompe os outros** — o laço captura e registra por tenant.

**Limite conhecido, com barreira:** evento que não implemente `EventoDeDominio` não pode ser
atribuído a um tenant e portanto nunca seria reenviado. `ArquiteturaTest` reprova o build nesse
caso, para que o limite não vire pendência eterna e silenciosa.

## 7. Retenção

`completion-mode=archive`. Concluída, a publicação sai da tabela quente e vai para
`event_publication_archive`.

| Modo | Por que não |
|---|---|
| `DELETE` | Apaga a resposta para "esta notificação saiu?", que é a pergunta real do suporte |
| `UPDATE` (padrão) | Deixa a tabela quente crescer para sempre — risco R-12 |
| **`ARCHIVE`** | Guarda o rastro e mantém a tabela quente pequena. Move o crescimento, então exige expurgo |

`PurgadorDoOutbox` remove o arquivo com mais de 14 dias, diariamente, pela conexão de manutenção.

## 8. Observabilidade

Fila travada é falha **silenciosa**: nenhuma exceção, nenhum 5xx, Sentry quieto — e a confirmação
simplesmente não chega. É um dos dois modos de falha silenciosa do projeto; o outro é cache
servindo dado velho.

| Métrica | Por quê |
|---|---|
| `outbox.pendentes` | Quantas publicações não concluíram |
| `outbox.pendente.idade.segundos` | **A que importa.** Fila saudável e movimentada também tem pendências a qualquer instante; o que distingue movimento de paralisia é a idade da mais antiga |

Alerta prometido em `12-observabilidade`: outbox parado há mais de 5 min.

## 9. Dados

`V5__outbox.sql` traz o schema **v2** do `spring-modulith-events-jdbc` 2.1.x, copiado para a
migration. O inicializador automático fica desligado: schema deste projeto nasce em migration
versionada.

> **Ao subir a versão do Modulith, conferir se o schema mudou.** Ele já mudou uma vez — o v1 não
> tem `status`, `completion_attempts` nem `last_resubmission_date` — e usar o antigo quebra em
> runtime, não no build.

## 10. Testes

- [x] `evento_entregue_com_tenant_restaurado` — prova o `TaskDecorator`
- [x] `rollback_nao_entrega` — nada sai e nada fica registrado
- [x] `pendencia_e_reenviada` — ouvinte falha, publicação fica pendente, reenviador reentrega
- [x] `reenvio_e_por_tenant` — pendência de outro estabelecimento fica intocada
- [x] `purga_do_arquivo`

## 11. O que a implementação revelou

**Campo de bean proxiado não é o campo do alvo.** `@ApplicationModuleListener` faz o Spring
envolver o bean num proxy CGLIB, criado **sem passar pelo construtor**: os campos do proxy ficam
nos valores padrão. `ouvinte.recebido` devolvia `null` mesmo com o alvo inicializado — chamada de
método é delegada ao alvo, leitura de campo não. Todo estado do ouvinte de teste passou a ser lido
por método.

**Latch dispara antes da conclusão.** O ouvinte aciona o latch dentro do próprio corpo — antes de
a transação dele commitar e de o Modulith marcar a publicação como concluída. Assertar o contador
logo depois é corrida, e corrida em teste vira intermitência que custa horas depois. As asserções
sobre pendências passaram a esperar a condição, com teto.

## 12. Pendências

- [ ] Alerta de `outbox.pendente.idade.segundos > 300` no Prometheus — a métrica existe, o alerta
      ainda não (entra em RT-INF-008)
- [ ] `docs/runbook/outbox-travado.md`: diagnóstico e acionamento manual de
      `ReenviadorDeEventos.executar`
- [ ] Publicação que falha repetidamente hoje é reenviada para sempre. Falta teto de tentativas e
      uma DLQ — `completion_attempts` já está na tabela, falta usar
- [ ] Externalização (`@Externalized`) só entra se algum dia houver broker; hoje é ruído
