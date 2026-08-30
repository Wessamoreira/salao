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

## Situação — um marco por fechamento de fase

O número corrente sai de `python3 ops/scripts/checklist.py`. A tabela abaixo existe para mostrar a
**tendência**, que é o que um número isolado esconde.

| Data | Rotinas no plano | Concluídas | Pendências abertas | Saltos de ordem |
|---|---|---|---|---|
| 29/08/2026 (criação) | 84 | 10 | 67 | 4, na catraca |
| 30/08/2026 (fim da Fase 0) | 90 | 20 | 111 | 4, na catraca |

**A dívida cresce mais rápido que a entrega, e isso é esperado nesta fase — mas não para sempre.**
Dez rotinas a mais trouxeram 44 pendências: cada uma que fecha enxerga o que a próxima vai precisar.
É dívida *declarada*, muito melhor que dívida invisível, e boa parte se resolve sozinha quando a
rotina dependente chega.

O que vigiar é a proporção. Se ao fim da Fase 1 as pendências continuarem subindo na mesma
velocidade, o sinal deixa de ser "estamos descobrindo o sistema" e passa a ser "estamos declarando
pronto o que não está". A revisão da lista inteira ao fim de cada fase é obrigatória, e o que
tiver crescido vira rotina no plano.

O plano subiu de 84 para 90 porque quatro rotinas foram **descobertas escrevendo outras**
(consentimento LGPD, anonimização, tela de clientes, tela de configuração do salão) — três delas
já eram citadas pela documentação como se existissem.

## O que ele não confere

Não julga se a rotina foi **bem** feita — para isso existem os testes, a revisão e a Definition of
Done em `CLAUDE.md`. Ele só garante que nada suma do radar em silêncio.
