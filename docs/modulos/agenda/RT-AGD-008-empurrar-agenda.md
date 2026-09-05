---
id: RT-AGD-008
titulo: Empurrar agenda a partir de um horário
modulo: agenda
fase: 1
perfil: completo
status: implementado
depende_de: [RT-AGD-003]
permissoes: [agenda:write:all]
eventos: []
regras: [RN-AGD-002, RN-AGD-004, RN-AGD-005, RN-AGD-021]
atualizado_em: 2026-09-04
---

# RT-AGD-008 — Empurrar a agenda

## 1. Objetivo

A Ana chegou meia hora atrasada. Seis clientes na agenda dela.

Sem esta rotina, a recepção arrasta seis blocos à mão enquanto as seis ligam perguntando — e cada
arrasto é uma transação que pode falhar sozinha, deixando a agenda num estado que ninguém escolheu.

## 2. Tudo ou nada

É a decisão central, e é a pendência que RT-AGD-003 registrou.

**Uma agenda empurrada pela metade é pior do que uma agenda não empurrada.** A recepção não
consegue dizer em que estado ela está, e já ligou para parte das clientes avisando um horário novo
que talvez não exista.

Então uma transação só. Se qualquer atendimento não couber, **nada se move** e o erro nomeia qual
barrou — que é o que a recepção precisa para decidir: cancela aquele, fala com a cliente, ou empurra
menos.

## 3. Apaga todos, depois grava todos

Empurrar para frente move o primeiro para cima do segundo.

Movê-los um a um exigiria ir **do último para o primeiro** — e do primeiro para o último quando o
empurrão é negativo. É exatamente o tipo de detalhe que se acerta na implementação e se perde na
primeira manutenção.

Apagando os blocos de **todos** antes de gravar qualquer um, a ordem deixa de importar. É a lição
de RT-AGD-003 (*"o agendamento colide consigo mesmo"*) aplicada a um conjunto.

## 4. Sair da jornada é a consequência esperada, não um erro

Se a Ana atrasou meia hora, o dia dela termina meia hora depois.

Recusar o empurrão porque o último atendimento passa das 18h tornaria a rotina inútil **no caso em
que ela mais serve**. Então o empurrão *implica* forçar a jornada, com o atraso registrado como
justificativa na trilha — e a trilha diz `forcouForaJornada: true` explicitamente, para não ficar
implícito.

O que o empurrão **não** implica é conflito: cair em cima do atendimento de outra pessoa, do
lavatório dela ou de um bloqueio continua sendo recusa. A diferença é que a jornada é uma
informação sobre o dia da profissional, e o conflito é sobre o dia de outra pessoa.

## 5. O que não anda junto

| Situação | Por quê |
|---|---|
| `EM_ATENDIMENTO` | A cliente já está na cadeira |
| Terminais | Mover o passado reescreveria o que aconteceu |
| Antes do horário pedido | Não é atraso, é o que já passou |
| Outra profissional | O atraso é de uma pessoa |

## 6. Um evento para o conjunto

`AgendaEmpurrada`, e não um `AgendamentoReagendado` por atendimento.

A diferença aparece em `notificacao`: seis eventos separados viram seis mensagens para seis
clientes, cada uma dizendo "seu horário mudou" sem contexto. Um evento de empurrão permite ao
consumidor tratar o conjunto — e, principalmente, saber que **a causa foi uma só**.

## 7. Testes

- [x] `EmpurrarAgendaIT` — 17: **o dia inteiro anda junto** · **empurrar para frente não colide
      consigo mesmo** · só a partir do horário pedido · só a agenda pedida · **puxar para trás** ·
      **se um não cabe, nada se move** · **o erro nomeia o culpado** · sair da jornada não reclama ·
      o que está em atendimento fica · cancelado não anda · agenda vazia devolve vazio ·
      justificativa obrigatória · deslocamento maior que um dia · empurrar zero · **um evento para
      o conjunto** · a trilha diz que forçou · isolamento entre salões

## 8. Pendências

- [ ] **Sem trava otimista por atendimento.** A recepção empurra "tudo a partir das 9h" sem olhar
      versão nenhuma — exigi-las tornaria a operação inutilizável. O efeito é que um reagendamento
      concorrente de um dos empurrados é sobrescrito em silêncio. A janela é pequena (a leitura e a
      escrita estão na mesma transação) mas existe
- [ ] **Não avisa as clientes.** O evento sai; `notificacao` não existe para consumi-lo, e é
      justamente nesta rotina que o aviso mais importa — seis pessoas com horário novo
- [ ] **Não sugere quanto empurrar.** A recepção digita os minutos. O sistema sabe a que horas a
      Ana chegou (ou saberia, com RT-ATD-001) e poderia propor
- [ ] **Não há empurrão do salão inteiro** — "faltou luz, tudo uma hora para frente" seria por
      estabelecimento e não por profissional. Cada agenda hoje é uma chamada
- [ ] **Cancelamento em lote continua faltando** (registrado em RT-AGD-005): "a Ana faltou hoje,
      cancele o dia dela" é a operação irmã desta, e não existe
