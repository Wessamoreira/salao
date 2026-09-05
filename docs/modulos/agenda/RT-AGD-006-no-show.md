---
id: RT-AGD-006
titulo: Registrar no-show
modulo: agenda
fase: 1
perfil: completo
status: implementado
depende_de: [RT-AGD-002]
permissoes: [agenda:write:all]
eventos: [EV-AGD-005]
regras: [RN-AGD-006, RN-AGD-007, RN-AGD-018]
atualizado_em: 2026-09-04
---

# RT-AGD-006 — No-show

## 1. Objetivo

Registrar que a cliente não apareceu, e quanto tempo de profissional isso custou.

## 2. Não se falta a um compromisso que ainda não começou

É a regra que distingue esta rotina do cancelamento, e ela cabe numa frase:

| | Sobre o quê | Quando se sabe |
|---|---|---|
| Cancelar | O futuro — a cliente avisa que não vem | Antes |
| No-show | O passado — ela não veio | Depois da hora |

Marcar antes registraria uma ausência que **não aconteceu**, num status terminal que não teria como
ser desfeito quando a cliente chegasse na hora certa.

O relógio vem do port `Relogio` (RN-INF-006) — é o que torna a regra testável sem esperar.

## 3. Basta ter começado, não terminado

Às 13h15 de um horário marcado para as 13h, a recepção já sabe.

Exigir o fim (14h40 numa coloração) faria o salão esperar uma hora e meia para registrar o que já
era evidente — e o resto do horário, que poderia receber um encaixe, ficaria bloqueado esse tempo
todo.

## 4. O número precisa ser levado a sério

O evento carrega `minutosPerdidos`, e são os **minutos do profissional**, não a duração total.

Numa coloração de 100 minutos, 40 eram pausa e a profissional estaria livre de qualquer jeito.
Contar os 100 inflaria o prejuízo — e tiraria a credibilidade do número justamente na conversa em
que ele precisa ser levado a sério: *"esta cliente faltou três vezes e me custou três horas"*.

O evento é interno e vai para `cliente`, porque quem atende no balcão está olhando a ficha da
pessoa, não a grade de duas semanas atrás.

## 5. Cancelado não vira falta

Quem avisou que não vinha **não faltou**. São coisas diferentes, e misturá-las estragaria
exatamente o número que sustenta a conversa.

A máquina de estados já dizia isso (`CANCELADO` é terminal); o teste
`cancelado_nao_vira_falta` fixa o porquê.

## 6. Testes

- [x] `RegistrarNoShowIT` — 9: **não se marca falta no futuro** · **basta ter começado, não
      terminado** · a falta libera o horário e os blocos acompanham · **os minutos perdidos
      descontam a pausa** · quem está sendo atendido não faltou · **cancelado não vira falta** ·
      falta é terminal · versão desatualizada · isolamento entre salões

## 7. Pendências

- [ ] **A cliente que chega depois do no-show não tem caminho.** A recepção marca falta às 13h15 e
      a cliente aparece às 13h20 — acontece. Hoje o status é terminal e a saída seria criar outro
      agendamento, deixando uma falta indevida no histórico, que é justamente o dado que a rotina
      existe para tornar confiável. Precisa de decisão do dono: anular a falta (contradizendo a
      máquina de estados) ou marcá-la como desconsiderada
- [ ] **Não há tolerância configurável.** Hoje qualquer instante depois do início serve. Quanto
      esperar antes de desistir é decisão do salão — e um campo `tolerancia_minutos` no
      estabelecimento seria o lugar, junto da granularidade da agenda
- [ ] **Nada é automático.** Um horário que passou sem ninguém marcar nada fica `CONFIRMADO` para
      sempre. Uma varredura que marcasse faltas sozinha é tentadora e perigosa — marcaria falta de
      quem foi atendida e ninguém registrou. Depende de RT-ATD-001 existir
- [ ] **`cliente` ainda não consome o evento**, então ele não é durável (mesma situação das
      rotinas anteriores) e o histórico de faltas não existe em lugar nenhum
- [ ] **Nenhuma consequência.** Taxa, exigência de sinal, bloqueio de agendamento online para quem
      falta muito — tudo isso é política, e nenhuma foi decidida
