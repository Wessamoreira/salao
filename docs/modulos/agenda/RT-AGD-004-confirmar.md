---
id: RT-AGD-004
titulo: Confirmar agendamento
modulo: agenda
fase: 1
perfil: completo
status: implementado
depende_de: [RT-AGD-002]
permissoes: [agenda:write:all]
eventos: [EV-AGD-002]
regras: [RN-AGD-006, RN-AGD-007, RN-AGD-013]
atualizado_em: 2026-09-04
---

# RT-AGD-004 — Confirmar

## 1. Objetivo

Tirar o agendamento de `SOLICITADO` — o estado em que nasce quem marcou sozinho pela web ou pelo
WhatsApp (RN-AGD-013).

## 2. Confirmar de novo não é erro

A cliente recebe o lembrete e clica duas vezes. A recepção confirma por telefone o que a cliente já
tinha confirmado pelo link.

Nos dois casos **o estado desejado já vale**, e devolver erro mostraria falha para quem fez tudo
certo. Então confirmar um `CONFIRMADO` devolve o agendamento como está.

Isso não afrouxa RN-AGD-007: a transição `CONFIRMADO → CONFIRMADO` continua não existindo, nada é
gravado, a versão não muda e **nenhum evento sai** — o teste `confirmar_duas_vezes` verifica os
três. O que muda é a resposta a quem pediu: "já está assim" em vez de "não pode".

Qualquer outro estado de origem é `ER-AGD-TRANSICAO_INVALIDA` (409). Confirmar um cancelado
devolveria à agenda um horário que já foi oferecido a outra pessoa.

## 3. Cabeça e blocos, sempre juntos

RN-AGD-006, e é a razão de esta rotina pequena importar.

Confirmar **não muda a ocupação** — `SOLICITADO` e `CONFIRMADO` ocupam a agenda igualmente. Mas o
caminho precisa ser o mesmo de cancelar, onde muda. E o único jeito de ter certeza de que será o
mesmo é **não haver outro caminho**: `AgendaJdbc.alterarStatus` muda a cabeça e os blocos, ou
nenhum dos dois.

Se os blocos ficassem para trás aqui, ficariam para trás em RT-AGD-005 — e um agendamento cancelado
continuaria bloqueando o horário, sem erro, até a cliente ligar perguntando por que não consegue
marcar.

**A regra proíbe gatilho**, com o motivo escrito: *"regra invisível não é testável em unidade e some
da revisão de código"*. Respeitei. O método com nome é a alternativa — visível, com um lugar só, e
com teste que confere os três blocos, inclusive o da pausa.

## 4. O evento distingue quem confirmou

`confirmadoPelaCliente` separa os dois caminhos que chegam ao mesmo status.

Quem consome precisa da diferença: mandar *"obrigado por confirmar"* para quem não confirmou nada é
pior do que não mandar nada.

## 5. A máquina de estados ganhou teste próprio

`StatusAgendamentoTest` varre as **36 combinações** de origem e destino contra uma tabela escrita à
parte, e não contra a implementação.

Um teste que só verificasse as transições válidas aprovaria uma implementação permissiva demais —
que é o defeito perigoso aqui, porque ele não aparece até alguém reabrir um cancelado.

## 6. Testes

- [x] `StatusAgendamentoTest` — 11: **a tabela completa, célula por célula (36 combinações)** ·
      os três terminais são terminais · nenhum status transiciona para si mesmo · **os que ocupam
      a agenda são os do `EXCLUDE`** · só o que vai acontecer pode ser movido · a origem decide o
      status inicial
- [x] `ConfirmarAgendamentoIT` — 7: **solicitado vira confirmado e os blocos acompanham** ·
      **confirmar de novo não é erro nem gera evento** · versão desatualizada · cancelado não se
      confirma · concluído não volta · **o evento diz quem confirmou** · isolamento entre salões

## 7. Pendências

- [ ] **O link de confirmação da cliente não existe.** Hoje `pelaCliente` é um booleano que quem
      chama informa — e quem chama precisa de `agenda:write:all`. O fluxo real (a cliente clicando
      num link do WhatsApp, sem login) depende de `notificacao` e de um token de uso único
- [ ] **Nada expira uma solicitação.** Um `SOLICITADO` que ninguém confirma ocupa o horário para
      sempre. Um prazo — "some da agenda se não confirmar em 24h" — precisa de decisão do dono
      antes de virar código
- [ ] **`OCUPAM_A_AGENDA` e o filtro das constraints são a mesma lista escrita duas vezes**, uma em
      Java e outra em SQL (V25). O teste confere o conteúdo do lado Java; se alguém alterar o SQL,
      nada acusa
- [ ] **`agenda:write:own` continua fora**, pela mesma razão das rotinas anteriores
