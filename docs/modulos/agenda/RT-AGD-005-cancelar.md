---
id: RT-AGD-005
titulo: Cancelar, com motivo
modulo: agenda
fase: 1
perfil: completo
status: implementado
depende_de: [RT-AGD-002]
permissoes: [agenda:write:all]
eventos: [EV-AGD-004]
regras: [RN-AGD-006, RN-AGD-007, RN-AGD-009, RN-AGD-017]
atualizado_em: 2026-09-04
---

# RT-AGD-005 — Cancelar

## 1. Objetivo

Tirar o compromisso da agenda de duas pessoas, **devolvendo o horário**, e registrar por quê.

## 2. É aqui que RN-AGD-006 finalmente importa

`CANCELADO` não está em `OCUPAM_A_AGENDA` — o mesmo conjunto do filtro das exclusion constraints.
Mudar o status **dos blocos**, e não só o da cabeça, é o que faz o horário ser oferecido de novo.

RT-AGD-004 exercitou esse caminho sem que ele mudasse nada (os dois status ocupavam a agenda
igualmente). Esta é a primeira rotina em que mudar de verdade importa, e por isso ela carrega o
teste que a regra nomeia: `cancelar_libera_todos_os_blocos_para_novo_agendamento`.

**O recurso volta junto, sem código específico.** O filtro de status vale para as duas constraints,
então o lavatório reservado para uma coloração cancelada fica livre no mesmo instante. O teste
`cancelar_libera_o_recurso` prova os dois lados: antes do cancelamento a Júlia não consegue marcar
(o único lavatório está tomado), depois consegue.

## 3. Cancelar de novo NÃO é como confirmar de novo

Em RT-AGD-004, confirmar o que já estava confirmado devolve o estado atual — o pedido era inócuo, e
recusá-lo mostraria falha para quem fez tudo certo.

Aqui a simetria seria um erro. O segundo cancelamento chega com **outro motivo e outra autoria**, e
aceitá-lo em silêncio sobrescreveria o registro do que de fato aconteceu — ou o descartaria sem
dizer. Cancelar um cancelado é `ER-AGD-TRANSICAO_INVALIDA`.

É a mesma pergunta com respostas opostas, e a diferença é se o segundo pedido **carrega informação
nova**.

## 4. Terminal é terminal

Não existe "descancelar". Desfazer devolveria à agenda um horário que, entre o cancelamento e o
arrependimento, pode ter sido oferecido e vendido a outra pessoa — e aí duas clientes teriam a
mesma cadeira, que é a reclamação nº 1 do caderno.

Quem se arrependeu marca de novo, e o sistema dirá honestamente se o horário ainda existe.

## 5. O motivo é obrigatório, e a autoria é coluna

Sem o motivo, o histórico da cliente vira uma coluna de "cancelado" sem sentido.

E `cancelado_pela_cliente` parece detalhe: é a informação que o salão mais usa. *"A Marilda
desmarcou três vezes este mês"* é uma conversa que o dono precisa ter; *"eu remarquei a Marilda três
vezes porque a Ana faltou"* é outra completamente diferente. Sem a coluna, as duas viram a mesma
linha no relatório.

Fica na tabela e não só na trilha porque é dado de negócio, consultado por cliente e por período —
e porque a trilha é append-only, não indexada para isso, e fora do alcance da anonimização
(RN-CLI-007), o que a torna o lugar errado para o que se quer consultar de propósito.

Motivo e autoria são gravados **na mesma escrita do status**: um cancelamento registrado sem o
motivo, ainda que por um instante, já nasceu incompleto — e não há segunda chance, porque o status
é terminal.

## 6. Um evento, não dois

RN-AGD-009 previa `VagaLiberada` além de `AgendamentoCancelado`. Seriam **dois eventos com o mesmo
conteúdo disparados no mesmo instante**.

`AgendamentoCancelado` carrega a janela liberada — profissional, serviço, início e fim — que é
exatamente o que a fila de espera precisa para achar quem cabe ali. Um evento que só dissesse
"cancelado" obrigaria o consumidor a buscar o agendamento de volta, e ele acabou de deixar de
existir para a agenda.

Fica registrado aqui para quem implementar RT-AGD-010.

## 7. `PATCH /cancelamento`, não `DELETE`

`DELETE` seria a rota "natural" e está errada: cancelar não apaga nada. O agendamento cancelado é
justamente o registro que sustenta a conversa sobre quem desmarca demais.

## 8. Testes

- [x] `CancelarAgendamentoIT` — 11: **cancelar libera todos os blocos para um novo agendamento** ·
      **o recurso volta junto** · os blocos ficam com o status da cabeça · **cancelar sem motivo é
      recusado** · motivo e autoria gravados na mesma escrita · **cancelar um cancelado é recusado**
      · atendimento em curso pode ser cancelado · concluído não se cancela · versão desatualizada ·
      **o evento leva a janela liberada** · isolamento entre salões

## 9. Pendências

- [ ] **Não há política de prazo.** "Cancelamento com menos de 24h cobra taxa" aparece como exemplo
      no javadoc do `Relogio` e não existe em lugar nenhum. Precisa de decisão do dono antes de
      virar código: qual prazo, qual taxa, e quem pode dispensá-la
- [ ] **Nada avisa a cliente.** O evento sai; `notificacao` não existe para consumi-lo, então ele
      não é durável (mesma situação de RT-AGD-002)
- [ ] **A fila de espera não é chamada** — RT-AGD-010. O evento já carrega tudo de que ela precisa
- [ ] **Cancelamento em lote não existe** — "a Ana faltou hoje, cancele o dia dela" é uma operação
      real e seriam N transações independentes hoje. Parente de RT-AGD-008
- [ ] **`agenda:write:own` continua fora**, pela mesma razão das rotinas anteriores
