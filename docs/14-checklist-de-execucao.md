# Checklist de execução

> **Por que este arquivo existe.** Em 29/08/2026 o bloco `RT-IAM-001..008` foi pulado: as rotinas
> `RT-INF-005` a `RT-INF-008` foram construídas com a autenticação inteira por fazer. Nada avisou.
> O desvio só apareceu quando escrever o primeiro caso de uso de negócio esbarrou na falta de
> `@PreAuthorize`.
>
> A lição não é "prestar mais atenção". É que **um plano não avisa quando está sendo desobedecido**
> — e a resposta a isso é a mesma que o projeto já aplica a fronteira de módulo e a RLS: um teste.

## Como consultar

```bash
python3 ops/scripts/checklist.py
```

```bash
python3 ops/scripts/checklist.py --pendencias
```

O script lê `09-plano-de-implementacao.md` e o cabeçalho YAML de cada `RT-*.md`. Não há lista
mantida à mão em lugar nenhum — lista paralela diverge, e uma lista de progresso que mente é pior
que nenhuma.

## O que ele confere

| Verificação | Por quê |
|---|---|
| Rotina documentada que não está no plano | Ou o plano envelheceu, ou apareceu trabalho que ninguém decidiu fazer |
| Rotina sem cabeçalho YAML | Sem cabeçalho ela é invisível para toda contagem |
| **Salto de ordem** — concluída com uma anterior da mesma fase por iniciar | É exatamente o erro que aconteceu |
| Pendências abertas dentro de rotinas já entregues | Mede a dívida de quem declarou "pronto" |

## A catraca

`ops/scripts/saltos-aceitos.txt` registra os saltos já cometidos e conscientemente aceitos. O
script falha em qualquer salto **fora** dessa lista.

Fazer o CI falhar hoje pelo desvio já conhecido seria nascer vermelho — e pipeline vermelho por
coisa conhecida ensina a ignorar vermelho, até o vermelho que importa passar despercebido. Com a
catraca, o passado fica registrado e o futuro fica protegido.

**Acrescentar um ID ali é uma decisão, não um atalho:** obriga a escrever por que a ordem do plano
não valeu naquele caso.

## Situação em 29/08/2026

| | |
|---|---|
| Rotinas no plano | **84** |
| Concluídas | **10** |
| Pendências abertas dentro do que já foi entregue | **67** |
| Saltos de ordem | 4, todos registrados na catraca |

**Os 67 merecem atenção.** Não são tarefas esquecidas: são o que cada rotina deixou explicitamente
anotado ao ser fechada — bloqueio por IP no login, DLQ no outbox, `lock_timeout` na idempotência,
máquina de homologação, `traceId`. É dívida declarada, o que é muito melhor que dívida invisível,
mas continua sendo dívida. Rever a lista ao fim de cada fase, e promover a rotina o que tiver
crescido.

## O que ele não confere

Não julga se a rotina foi **bem** feita — para isso existem os testes, a revisão e a Definition of
Done em `CLAUDE.md`. Ele só garante que nada suma do radar em silêncio.
