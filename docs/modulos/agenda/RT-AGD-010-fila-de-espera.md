---
id: RT-AGD-010
titulo: Fila de espera
modulo: agenda
fase: 1
perfil: parcial
status: implementado-parcial
depende_de: [RT-AGD-005]
permissoes: [agenda:write:all, agenda:read:all]
eventos: []
regras: [RN-AGD-009, RN-AGD-023]
atualizado_em: 2026-09-04
---

# RT-AGD-010 — Fila de espera

> **Parcial de propósito.** A chamada automática do título do plano depende de `notificacao`, que
> não existe. Ver a seção 5.

## 1. Objetivo

A cliente quer quinta às 14h e está tomado. Hoje a recepção anota num papel ao lado do telefone — e
o papel é justamente o que se perde quando a vaga abre uma semana depois, com outra pessoa no
balcão.

## 2. Janela, não horário

A espera é um **intervalo de dias** mais uma faixa de hora opcional.

"Quinta que vem à tarde" é o que a cliente diz. Exigir que ela escolha 14h ou 14h30 transformaria a
fila numa lista de horários específicos que quase nunca abrem exatamente — e a fila só serve se ela
casar com o que de fato cancela.

Pelo mesmo motivo, `profissionalId` nulo significa "qualquer um", e é **o caso em que a fila mais
funciona**: qualquer cancelamento serve.

## 3. A fila expira sozinha

Não há job de limpeza, e isso é decisão e não omissão.

A busca por candidatos filtra pelo intervalo de dias que a cliente aceitou, então uma espera cuja
janela já passou simplesmente **para de aparecer**. Uma fila que dependesse de um job para dar a
resposta certa daria a resposta errada exatamente quando o job falhasse — e ninguém percebe um job
que não roda até alguém ser chamado para um horário de duas semanas atrás.

A entrada vencida continua visível em `naFila()`, para quem quiser ver e resolver. Ela só não é
mais oferecida.

## 4. Duas saídas, e a diferença importa

`atendida_em` e `desistiu_em`, não um campo só nem um `delete`.

*"Conseguiu horário"* é o sistema funcionando; *"cansou de esperar"* é a fila sendo longa demais.
Apagar a linha, ou marcar as duas do mesmo jeito, jogaria fora justamente o número que diz se a fila
está servindo para alguma coisa.

## 5. O que esta rotina NÃO faz

**Não avisa ninguém.** A chamada automática — notificar o primeiro da fila e dar um prazo antes de
passar ao seguinte — depende de `notificacao`, que é Fase 1 adiante.

E a alternativa tentadora seria pior: registrar uma "oferta enviada" sem enviar nada **grava uma
mentira** que o relatório depois trataria como verdade, e a cliente que "foi avisada" e não atendeu
apareceria como quem ignorou o salão.

O que existe é `candidatosA(servico, profissional, inicio)`: *cancelou este horário, veja quem
cabe*. É como um salão pequeno de fato trabalha — a recepção liga — e é o insumo exato de que a
automação vai precisar quando houver como avisar.

Ordem de chegada, sempre. Sem isso, "quem avisamos primeiro" viraria a ordem em que o banco
devolveu, e a cliente que espera há duas semanas ficaria atrás da que entrou ontem.

## 6. A hora é relógio de parede

A faixa de hora é comparada em `LocalTime` no fuso do salão, não em instante. A cliente disse
"quinta à tarde", que é uma frase sobre o relógio da parede — comparar instantes faria a faixa
escorregar uma hora na virada do horário de verão.

Daí a divisão: o banco filtra o que dá para filtrar por índice (serviço, profissional, intervalo de
dias) e o domínio decide a hora. Um `where` sobre isso exigiria converter o instante dentro do SQL,
com o fuso vindo de outra tabela — mais fácil de errar e impossível de testar sem banco.

## 7. Testes

- [x] `EsperaNaFilaTest` — 10: serve quando tudo bate · **sem profissional, qualquer um serve** ·
      com profissional é exclusivo · outro serviço nunca serve · sem faixa, o dia inteiro · **a
      faixa é fechada no início e aberta no fim** · o intervalo de dias é fechado dos dois lados ·
      **a comparação é relógio de parede** · uma hora só não é faixa · janela invertida
- [x] `FilaDeEsperaIT` — 13: entra com a janela aceita · **não entra duas vezes** · reentrada
      depois de sair é legítima · **atendida e desistiu são saídas diferentes** · não sai duas
      vezes · **candidatos em ordem de chegada** · a faixa de hora filtra · a profissional filtra ·
      **a janela vencida some sozinha** · quem saiu não é candidata · janela invertida é 422 ·
      cliente de outro salão · isolamento entre salões

## 8. Pendências

- [ ] **A chamada automática não existe** — é o que falta para a rotina deixar de ser parcial.
      Depende de `notificacao` e de decidir o prazo de resposta antes de passar ao próximo
- [ ] **Nada liga o cancelamento à fila.** `AgendamentoCancelado` já carrega a janela liberada
      (decisão de RT-AGD-005), e não há ouvinte. Quando houver, ele também tornará aquele evento
      durável — hoje o Modulith não grava publicação sem ouvinte
- [ ] **As ocorrências recusadas de RT-AGD-009 não entram na fila** — era o destino natural delas,
      e hoje `naoCouberam` volta na resposta e some
- [ ] **Marcar "atendida" é manual.** Criar um agendamento para quem está na fila não a tira de
      lá. Automatizar é tentador e errado: ela pode ter marcado outra coisa
- [ ] **Não há limite de tamanho nem aviso de fila longa** — vinte pessoas esperando pela mesma
      quinta é informação que o dono deveria ver
