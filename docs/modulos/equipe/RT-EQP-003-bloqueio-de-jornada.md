---
id: RT-EQP-003
titulo: "Exceção de jornada: feriado, férias, curso, atestado"
modulo: equipe
fase: 1
perfil: completo
status: implementado
depende_de: [RT-EQP-002]
permissoes: [usuario:manage]
eventos: []
regras: [RN-EQP-009, RN-EQP-010, RN-EQP-011, RN-EQP-012]
atualizado_em: 2026-08-30
---

# RT-EQP-003 — Exceções de jornada

> **A rota mudou em RT-AGD-007.** Era `/api/v1/bloqueios`; virou `/api/v1/bloqueios-de-jornada`
> quando a agenda ganhou o seu próprio bloqueio — o da grade, que ocupa horário e vale para recurso.
> "Bloqueio" sozinho passou a significar duas coisas, e a rota curta ficaria com a menos procurada.

## 1. Objetivo

Registrar quando alguém **não** atende, e entregar à agenda a resposta que ela realmente precisa:
o que sobra da jornada num dia concreto.

Com esta rotina, o módulo `equipe` passa a responder `intervalosEm(profissional, data)` — jornada
do dia menos os bloqueios. É o insumo direto de RT-AGD-001.

## 2. O trabalho de verdade é subtração de intervalos

A jornada diz "terça das 9h às 19h". O curso diz "dia 14, das 14h às 16h". A resposta é
**"9h-14h e 16h-19h"** — dois intervalos onde havia um.

Um bloqueio pode cortar o começo, o fim, o meio (partindo o intervalo em dois), o intervalo
inteiro, ou nada. Cada um desses é um jeito diferente de a agenda mostrar horário errado, e um
modelo de "dia livre ou dia ocupado" erraria o caso mais comum de todos.

Por isso `SubtracaoDeIntervalos` é domínio puro, com doze testes próprios: é a parte com mais
casos de borda da rotina e a mais barata de testar sozinha.

## 3. Feriado não é exceção de uma pessoa

`profissional_id` nulo significa **o salão inteiro** (RN-EQP-009).

Modelar feriado por profissional obrigaria a criar uma linha para cada um — e a esquecer de criar
para quem fosse contratado depois. O salão apareceria aberto no Natal para a profissional nova, e
ninguém descobriria até uma cliente marcar.

## 4. Sobreposição aqui é normal — e é o contrário da jornada

Na jornada, dois intervalos cruzados são **erro de dado**: o profissional não pode estar em dois
turnos ao mesmo tempo, e o banco impede com `EXCLUDE` (RN-EQP-007).

Aqui, dois bloqueios cruzados são banais: férias que englobam um feriado, atestado dentro de uma
folga. Os dois removem disponibilidade e a soma dá o mesmo resultado. **Proibir obrigaria a
recepção a apagar o feriado para lançar as férias** (RN-EQP-010).

O contraste entre as duas tabelas é deliberado, e vale registrar: a mesma palavra — "sobreposição"
— descreve um erro num caso e o normal no outro. Copiar o `EXCLUDE` de uma para a outra por
simetria teria produzido um sistema que recusa o óbvio.

## 5. Dia inteiro ou um pedaço de um dia — e nada entre os dois

Hora nula é dia inteiro; com hora, o bloqueio vale para **um dia só** (RN-EQP-011).

Férias são dias cheios. Curso é um pedaço de um dia. O terceiro caso — "das 14h às 18h todos os
dias de uma quinzena" — é recusado, porque ninguém pede isso e aceitar obrigaria toda consulta de
disponibilidade a tratar um caso que não existe.

A restrição vale no caso de uso e como `check` no banco.

## 6. Aqui apaga mesmo, e é o certo

Contraria o "desativar, nunca apagar" de cliente (RN-CLI-008) e de profissional (RN-EQP-005).

O motivo é concreto: **nada aponta para um bloqueio**. Cliente e profissional têm histórico
financeiro dependente; um bloqueio não tem nada. E um bloqueio lançado por engano precisa sumir de
verdade, senão a agenda fica com um buraco que ninguém sabe explicar.

Regra copiada sem o motivo vira cerimônia.

## 7. Atestado é dado de saúde

O motivo vai para a trilha de auditoria; a **observação não** (RN-EQP-012).

"Cirurgia no joelho" digitado no campo livre não pode acabar numa tabela append-only, fora do
alcance de qualquer expurgo. É a mesma razão de RN-CLI-007 — e a terceira vez que ela decide um
detalhe de implementação, o que sugere que já é padrão do projeto e não caso isolado.

## 8. Testes

- [x] `SubtracaoDeIntervalosTest` — 12: **corte no meio parte em dois** · corte no começo · no fim ·
      cobre tudo · fora do expediente · **encostado não corta** · jornada com almoço · bloqueio que
      atravessa o almoço · **bloqueios sobrepostos entre si** · sem bloqueio sai em ordem · jornada
      vazia · resultado imutável
- [x] `BloqueioDeJornadaIT` — 15: dia sem bloqueio · **curso parte o expediente** · férias tiram
      dias inteiros · **feriado fecha para todo mundo** · bloqueio de um não afeta o outro ·
      sobrepostos são aceitos · dia sem jornada · remover devolve o horário · remover inexistente ·
      bloqueio por hora em um dia só · horas vão juntas · período invertido · **a observação de
      atestado não vai para a trilha** · isolamento entre salões · sem permissão é recusado

## 9. O que ficou de fora, e por quê

**Disponibilidade extra.** "Vou atender neste domingo excepcionalmente" é uma exceção de jornada
tão legítima quanto as ausências — e o plano pediu apenas as ausências (feriado, férias, curso,
atestado). Implementei o que foi pedido.

Registro aqui porque é uma decisão de escopo, não um esquecimento: quando entrar, será uma coluna
`tipo` com `BLOQUEIO` e `DISPONIBILIDADE_EXTRA`, e a consulta passa a somar depois de subtrair. A
tabela e a consulta já estão no formato certo para isso.

## 10. Pendências

- [ ] **Disponibilidade extra** (ver acima). O caso real é a noiva de sábado e o domingo antes do
      Natal, e hoje não há como registrar
- [ ] **Feriados nacionais não vêm prontos.** Cada salão vai lançar 25/12 à mão, todo ano. Uma
      lista dos feriados nacionais com um botão "importar" é barata e evita o esquecimento que
      fecha o salão sem avisar a agenda
- [ ] **Nada avisa sobre agendamentos existentes ao lançar um bloqueio.** Marcar férias em cima de
      horários já marcados vai acontecer, e é o momento em que o sistema precisa listar quem
      remarcar. Depende de a agenda existir (RT-AGD-002)
- [ ] **Não há tela**, como em toda a Fase 1 de equipe
- [ ] **`intervalosEm` faz duas consultas por dia** (jornada e bloqueios). Para uma grade de
      agenda com sete dias e dez profissionais, são 140 consultas. Vai precisar de uma versão por
      período antes de RT-AGD-011 — registrado agora porque a forma da API já permite acrescentá-la
      sem quebrar quem usa
