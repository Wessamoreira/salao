---
id: RT-AGD-007
titulo: Bloqueio de agenda (arrastar na grade, sem formulário)
modulo: agenda
fase: 1
perfil: completo
status: implementado
depende_de: [RT-AGD-002, RT-CAT-003]
permissoes: [agenda:write:all, agenda:read:all]
eventos: []
regras: [RN-AGD-004, RN-AGD-005, RN-AGD-010, RN-AGD-020]
atualizado_em: 2026-09-04
---

# RT-AGD-007 — Bloqueio de grade

## 1. Objetivo

Tomar um pedaço da grade sem cliente nenhum: "preciso sair às 15h", "o lavatório 2 está quebrado".

## 2. Não é o bloqueio de jornada, e a diferença tem consequência

`equipe` já tem um (RT-EQP-003). São coisas de natureza diferente:

| | Bloqueio de **jornada** (equipe) | Bloqueio de **grade** (agenda) |
|---|---|---|
| O que faz | Molda *quando a pessoa trabalha* | *Ocupa* um pedaço, como um atendimento |
| Alcance | Só profissional | Profissional **e recurso** |
| Forma | Data + hora de parede | Instante a instante |
| Onde entra | Cálculo de `intervalosEm` | Exclusion constraint |
| Forçável? | **Sim** — admin marca por cima com justificativa | **Não** — o banco recusa |

A última linha é a que importa. *"A Ana está em curso"* é informação sobre a agenda dela, e o dono
pode decidir marcar assim mesmo. *"O lavatório 2 está quebrado"* não é opinião — marcar por cima
produziria uma cliente com a cabeça ensaboada e nenhum lugar para lavar.

## 3. Uma constraint, um lugar para errar

O bloqueio **vira linha em `agendamento_bloco`**, com `tipo = 'BLOQUEIO'` e `agendamento_id` nulo.
A decisão já estava escrita em `04-modelo-de-dados.md` e se paga aqui.

Uma tabela própria com constraint espelhada teria **duas exclusões para manter coerentes**, e a
segunda seria a que ficasse para trás numa refatoração. Como linha do mesmo bloco, o bloqueio
disputa o profissional e o recurso pela mesma constraint que já protege os atendimentos: marcar por
cima é recusado pelo banco, **sem uma linha de código a mais em RT-AGD-002 nem em RT-AGD-003**.

E vale nos dois sentidos: bloquear por cima de um atendimento também é recusado. É o comportamento
certo — quem quer o horário livre cancela primeiro, avisando a cliente, em vez de fazê-la chegar e
descobrir.

O status é `CONFIRMADO` porque é o que coloca a linha dentro do filtro da constraint, e porque é
verdade: não existe bloqueio à espera de aprovação.

## 4. Apagar, não desativar

Um bloqueio não sustenta histórico nenhum — nenhum atendimento aponta para ele, nenhuma comissão o
usa. É a mesma decisão de RT-EQP-003: *apaga-se o que não sustenta nada*. O `on delete cascade` leva
a linha da grade junto e o horário volta no mesmo instante.

Aqui `DELETE` é a rota certa, ao contrário do cancelamento (RT-AGD-005), onde o registro cancelado é
justamente o que sustenta a conversa.

## 5. A colisão de rota que a rotina revelou

`equipe` mapeava `/api/v1/bloqueios`. Ao criar esta, o Spring recusou a subida com "ambiguous
mapping" — e o erro estava certo: *"bloqueio" passou a significar duas coisas*.

Renomeei **as duas**, e não só a nova:

- `/api/v1/bloqueios-de-jornada` (equipe)
- `/api/v1/bloqueios-de-agenda` (agenda)

Deixar a rota curta com a de jornada seria pior do que renomear: quem abre a grade e arrasta espera
`/bloqueios`, e ia achar a errada. Custou uma linha em cada controller porque nenhum front consome
nenhuma das duas ainda — daqui a seis meses custaria uma migração de cliente.

## 6. Sem formulário

O gesto é arrastar. O período vem do que foi desenhado; só o motivo precisa ser escolhido, e a
descrição é opcional.

Uma tela com seis campos obrigatórios faria a recepção anotar no papel — que é o que este sistema
inteiro existe para evitar.

## 7. Testes

- [x] `BloquearGradeIT` — 12: **marcar por cima de um bloqueio é recusado pelo banco** · o bloqueio
      some da disponibilidade · **encostar é permitido** · **bloquear por cima de um atendimento é
      recusado** · dois bloqueios não se sobrepõem · **o lavatório quebrado impede a coloração** ·
      bloqueio sem alvo é recusado · período invertido é 422 · profissional de outro salão é
      recusado · **remover devolve o horário no mesmo instante** · listar no período · isolamento
      entre salões

## 8. Pendências

- [ ] **`ALMOCO` como motivo é um remendo.** O almoço é ausência de intervalo na jornada
      (RN-EQP-006) e não deveria precisar de bloqueio. Ficou na lista porque a grade vai querer
      desenhar a faixa; se a jornada já a descreve, este motivo sai
- [ ] **Bloqueio recorrente não existe** — "toda quarta das 12h às 13h" hoje são 52 bloqueios. É
      parente de RT-AGD-009 (recorrência), e provavelmente a mesma máquina
- [ ] **Bloqueio de recurso sempre pega a unidade 1.** Com "Lavatório, quantidade 3", não há como
      dizer *qual* quebrou — bloquear os três exige três bloqueios. A alternativa seria um bloqueio
      que reduz a capacidade, e isso muda a conta de RN-AGD-014
- [ ] **Não há aviso de que o bloqueio derruba disponibilidade já oferecida** — quem viu o horário
      na tela há dois minutos vai receber 409 ao marcar. É o mesmo caso do 409 de RT-AGD-003
- [ ] **Não há tela**, como em toda a Fase 1
