---
id: RT-AGD-003
titulo: Reagendar / mover (drag & drop)
modulo: agenda
fase: 1
perfil: completo
status: implementado
depende_de: [RT-AGD-002]
permissoes: [agenda:write:all]
eventos: [EV-AGD-003]
regras: [RN-AGD-001, RN-AGD-002, RN-AGD-004, RN-AGD-005, RN-AGD-008, RN-AGD-014, RN-AGD-015]
atualizado_em: 2026-09-04
---

# RT-AGD-003 — Reagendar

## 1. Objetivo

Arrastar um agendamento na grade: para outro horário, para outra coluna, ou os dois.

## 2. O defeito mais fácil de escrever aqui

**Mover das 13h para as 13h15 é mover para um horário que se sobrepõe ao atual.**

Sem apagar os blocos antigos *antes* de gravar os novos, o agendamento colide **consigo mesmo** na
exclusion constraint — e a recepção vê "horário ocupado" apontando para o próprio atendimento que
está tentando mover. Funciona perfeitamente para mover de 13h para 16h, e quebra no movimento mais
comum de todos: arrastar um pouquinho.

A mesma armadilha existe na outra dimensão: com um único lavatório, mover as mechas quinze minutos
faria o agendamento disputar o lavatório com ele mesmo. Por isso o delete acontece **antes da
alocação de unidades**, e não só antes do insert.

Tudo na mesma transação. Se ela falhar depois do delete, o rollback devolve os blocos antigos e a
cliente continua marcada onde estava — `conflito_faz_rollback_completo` prova isso.

## 3. A duração pode mudar sozinha, e é correto

| Movimento | O que acontece com a duração |
|---|---|
| Mesma coluna | **Preserva** a atual, inclusive uma forçada na criação |
| Outra coluna | **Recalcula** com o ritmo de quem vai atender (RN-CAT-013) |
| `duracaoMinutos` no pedido | Vence sempre |

Arrastar da coluna da Ana (escova em 30) para a da Júlia (os 45 do catálogo) faz o bloco
**crescer**. É surpreendente de ver e é o que a grade precisa mostrar: esconder seria prometer um
horário que não existe.

Já quem move um bloco de 90 minutos quinze minutos para frente espera um bloco de 90 minutos. Sem a
regra de preservar, a duração forçada na criação se perderia no primeiro arrasto — silenciosamente.

## 4. Só se move o que ainda vai acontecer

`SOLICITADO` e `CONFIRMADO` (RN-AGD-015). Um atendimento `EM_ATENDIMENTO` não se arrasta: a cliente
já está na cadeira. Os terminais, muito menos — mover o passado reescreveria o que aconteceu.

## 5. Duas recepcionistas no mesmo bloco

`versao` obrigatória no pedido. A segunda a soltar recebe 409 e recarrega a grade.

Sem isso, as duas ganhariam e a última escrita venceria em silêncio — que é o pior resultado
possível, porque ninguém fica sabendo que houve disputa.

## 6. A extração que a rotina forçou

Criar e reagendar fazem **o mesmo trabalho**: materializar a receita no tempo, conferir jornada e
passado, alocar unidades de recurso. A única diferença é o que vem depois — inserir ou substituir.

Duas cópias divergiriam, e dá para prever qual: a de reagendar. Menos usada, menos testada, e a que
silenciosamente deixaria de checar a jornada em alguma refatoração futura. Virou `ReservaDeHorario`,
usada pelos dois — `CriarAgendamentoUseCase` encolheu de 300 para 207 linhas no processo, e os 19
testes dele continuaram passando sem alteração.

## 7. Testes

- [x] `ReagendarIT` — 13: **mover 15 minutos não conflita com o próprio agendamento** · **o recurso
      próprio é liberado antes da realocação** · **outra coluna adota o ritmo de quem atende** ·
      **mesma coluna preserva a duração forçada** · outra coluna sem habilidade é rejeitada ·
      conflito com outro agendamento · **o conflito não deixa o agendamento sem blocos** ·
      **versão desatualizada é recusada** · fora da jornada recusado e aceito ao forçar ·
      **atendimento em curso não se arrasta** · isolamento entre salões · **o evento leva o DE e o
      PARA** · a trilha guarda o movimento

## 8. Pendências

- [ ] **Não existe "mover em lote"** — RT-AGD-008 (empurrar a agenda quando a profissional atrasa)
      vai precisar mover vários de uma vez, e fazer N chamadas desta rotina seria N transações e N
      chances de metade passar
- [ ] **O 409 não sugere o próximo horário livre.** R-UX-20 pede "A Ana já tem atendimento das
      13:00 às 14:40. O próximo horário livre é 15:00" — a disponibilidade (RT-AGD-001) já sabe
      calcular isso; falta ligar
- [ ] **`agenda:write:own` continua fora**, pela mesma razão de RT-AGD-002: não há vínculo
      usuário↔profissional
- [ ] **Mudar o serviço não é reagendar** — é cancelar e criar. Se a grade um dia permitir trocar o
      serviço arrastando, esta rotina precisa de outra decisão
- [ ] **Não há tela**, como em toda a Fase 1
