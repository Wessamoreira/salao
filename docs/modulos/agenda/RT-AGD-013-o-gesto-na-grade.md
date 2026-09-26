---
id: RT-AGD-013
titulo: "Bloqueio de grade e recorrência na tela da agenda"
modulo: agenda
fase: 7
perfil: completo
status: implementado
depende_de: [RT-AGD-002, RT-AGD-003, RT-AGD-004, RT-AGD-005, RT-AGD-006, RT-AGD-007, RT-AGD-009, RT-AGD-010, RT-AGD-011]
permissoes: [agenda:read:all, agenda:write:all]
eventos: []
regras: [RN-AGD-003, RN-AGD-013, RN-AGD-018, RN-AGD-021]
atualizado_em: 2026-09-26
---

# RT-AGD-013 — O gesto na grade

## 1. Objetivo

Fazer a agenda **escrever**: arrastar num vazio para marcar ou bloquear, arrastar um atendimento
para mudá-lo de hora, e confirmar, cancelar ou registrar a falta sem sair da grade.

## 2. Contexto de negócio

A agenda é a tela que a recepção deixa aberta o dia inteiro, e hoje ela **só lê**. As próprias
pendências de RT-AGD-011 dizem isso, em ordem de tamanho:

> **Não se arrasta.** RT-AGD-003 existe no backend e a grade não a chama — arrastar é o gesto
> central da tela e é o maior buraco desta rotina.
>
> **Não se marca nada pela grade.** Clicar num vazio deveria abrir a marcação; hoje o painel só lê.
>
> **Confirmar e cancelar não estão no painel** — são os dois cliques mais frequentes da recepção.

E o bloqueio, entregue em RT-AGD-007, é um endpoint que ninguém alcança: *"o período vem do
gesto"*, diz o controlador — e o gesto não existia.

**Aqui, pela primeira vez em quatro rotinas de tela, nenhum endpoint falta.** Catálogo, equipe,
acessos, estoque e financeiro cada um escondia um caso de uso sem porta; a agenda não esconde
nada. Os dezessete casos de uso do módulo têm controlador, e o que falta é exclusivamente o gesto.

## 3. Atores e permissões

| Ator | Permissão | O que faz |
|---|---|---|
| Recepção / gerente / dono | `agenda:write:all` | marca, bloqueia, arrasta, confirma, cancela |
| Profissional | `agenda:write:own` | **ainda não filtra** — pendência que atravessa a fase |

A tela pergunta por `agenda:write:all` para decidir se desenha os gestos. Quem só lê vê a mesma
grade, sem arrasto e sem botão — e o servidor recusaria de qualquer forma (inviolável nº 6: a
tela esconde, o caso de uso garante).

## 4. Pré-condições

- [x] Grade do dia (RT-AGD-011) · marcar (RT-AGD-002) · reagendar (RT-AGD-003)
- [x] Confirmar (RT-AGD-004) · cancelar (RT-AGD-005) · falta (RT-AGD-006)
- [x] Bloqueio (RT-AGD-007) · recorrência (RT-AGD-009) · fila (RT-AGD-010)

## 5. Fluxo principal

1. A recepção **arrasta** num pedaço vazio da coluna de alguém. O período aparece desenhado
   enquanto ela arrasta.
2. Solta. Um painel pergunta **o quê**: marcar um atendimento ou bloquear o horário.
3. **Marcar**: cliente (por busca), serviço, e — se for o caso — *repetir toda semana, 8 vezes*.
4. **Bloquear**: motivo e, opcionalmente, uma descrição.
5. **Arrastar um atendimento** para outra hora muda o horário na hora, com a tarja seguindo o
   ponteiro. Trocar de profissional ainda não é arrasto — está nas pendências.
6. **Clicar num atendimento** abre o detalhe, que agora confirma, cancela e registra falta.

## 6. Fluxos alternativos

**A1 — Bloquear em cima de atendimento marcado.** O servidor recusa: *"há atendimento marcado
neste horário; cancele antes de bloquear"* (RT-AGD-007). A tela mostra a frase dele — quem quer o
horário livre cancela primeiro, avisando a cliente, em vez de fazê-la chegar e descobrir.

**A2 — O horário foi tomado entre ver e soltar.** `ER-AGD-CONFLITO_HORARIO` (409). A tarja volta
sozinha para onde estava, e a recusa aparece. É o caso que o arrasto otimista existe para tratar.

**A3 — Versão velha.** Confirmar, cancelar e reagendar levam a `versao` que a tela leu. Se alguém
mexeu antes, o servidor recusa e a grade recarrega.

**A4 — Dias da série que não couberam.** `SerieCriada.naoCouberam` traz os instantes recusados. A
tela os lista e oferece **pôr na fila**, um por vez (RT-AGD-010) — recusar a série inteira porque
a terceira quinta é feriado seria absurdo, e esconder as recusadas faria a recepção descobrir uma
a uma quando as clientes ligassem.

**A5 — Fora da jornada.** O servidor recusa, e a recusa oferece forçar com justificativa
(`forcarForaJornada`). A tela não força escondido: ela pergunta.

**A6 — Arrastar para o passado.** Mesma coisa: recusa com caminho, não bloqueio silencioso.

## 7. Regras de negócio aplicadas

| ID | Resumo em uma linha | Garantida em |
|---|---|---|
| RN-AGD-003 | Sem sobreposição, garantido por `EXCLUDE` no banco | constraint de `agendamento` |
| RN-AGD-013 | A origem decide o status inicial | `CriarAgendamentoUseCase` |
| RN-AGD-018 | Bloqueio por cima de atendimento é recusado | `BloquearGradeUseCase` |
| RN-AGD-021 | Cancelar exige motivo; a falta, não | `CancelarAgendamentoUseCase` |

**Nenhuma regra nova.** É a primeira rotina de tela desta fase em que não houve o que registrar —
e vale dizer por quê: o módulo `agenda` foi construído com a tela em mente desde RT-AGD-001, e os
contratos já tinham a forma do gesto. `BloqueiosDaGradeController` escreveu *"o período vem do
gesto"* antes de o gesto existir.

## 8. O que este documento decide de desenho

**O gesto define o período; o painel pergunta só o quê.** Uma tela com seis campos obrigatórios
faria a recepção anotar no papel — que é o que este sistema existe para evitar. É o que o próprio
controlador do bloqueio já dizia.

**Arrastar define o início; para o bloqueio, define também o fim.** Um atendimento tem duração
própria — a do serviço, com os blocos dele (RT-CAT-002) —, e deixar o arrasto mandar faria um
arrasto torto virar uma coloração de vinte minutos. O painel mostra em que hora termina, para que
a diferença entre o que se arrastou e o que se marcou seja visível, não escondida. Bloqueio não
tem duração própria: ali o arrasto é a resposta inteira.

**A recorrência mora dentro do ato de marcar.** "Toda semana, 8 vezes" é uma caixa na marcação,
não uma tela de séries. Uma tela à parte seria mais um lugar para esquecer de olhar — e a decisão
de repetir se toma no mesmo segundo em que se marca a primeira.

**O destino aparece antes de soltar** (R-UX-06): a faixa desenhada no arrasto no vazio, e a tarja
seguindo o ponteiro no reagendamento. Arrastar às cegas é o que transforma um gesto rápido num 409
e numa conversa. **O que ainda não aparece é o "não cabe" em vermelho** — a tela só descobre o
conflito quando o servidor responde, e isso está nas pendências.

**A tarja volta sozinha quando o servidor recusa.** O arrasto é otimista porque a agenda é uma
tela de segundos; e otimista sem volta visível é mentira. A recusa aparece com a frase do
servidor, e a tarja volta para onde estava.

**Remover bloqueio não pede confirmação; cancelar atendimento pede.** Um bloqueio não tem
ninguém do outro lado — errar custa um clique. Cancelar chega a uma cliente, e o motivo é
obrigatório porque é ele que sustenta a conversa sobre quem desmarca demais.

## 9. Contrato de API

Nenhuma rota nova. Todas já existem:

```http
POST   /api/v1/agendamentos                 { clienteId, profissionalId, servicoId, inicio,
                                              origem: PAINEL, forcarForaJornada?, justificativa? }
PUT    /api/v1/agendamentos/{id}/horario    { inicio, profissionalId?, versao }
PATCH  /api/v1/agendamentos/{id}/confirmacao { versao, pelaCliente }
PATCH  /api/v1/agendamentos/{id}/cancelamento { versao, motivo, pelaCliente }
PATCH  /api/v1/agendamentos/{id}/falta      { versao }

POST   /api/v1/bloqueios-de-agenda          { profissionalId, motivo, descricao, inicio, fim }
DELETE /api/v1/bloqueios-de-agenda/{id}

POST   /api/v1/recorrencias                 { clienteId, profissionalId, servicoId,
                                              primeiroInicio, frequencia, quantidade }
POST   /api/v1/recorrencias/{id}/fila-de-espera { dia }
```

`origem: PAINEL` sempre: é o balcão, e é ele que decide o status inicial (RN-AGD-013). A tela não
oferece escolher a origem — marcar pela grade **é** marcar pelo balcão, e um seletor ali só
produziria origem errada.

**`SerieCriada.marcadas()` é derivado e não chega no JSON.** É o tamanho de uma lista que a tela
já tem, e contar o próprio array não é recriar regra — diferente de `quantosRecebem` ou
`faltando`, que são contas sobre o que o servidor sabe e a tela não.

**Erros**

| Código | Quando |
|---|---|
| `ER-AGD-CONFLITO_HORARIO` | 409 — horário tomado, ou bloqueio sobre atendimento |
| `ER-AGD-FORA_DA_JORNADA` | 422 — oferece forçar com justificativa |
| `ER-AGD-CONFLITO_DE_VERSAO` | 409 — alguém mexeu antes |
| `ER-AGD-BLOQUEIO_INVALIDO` | 422 — período invertido, alvo ausente |

## 10. Dados

Nenhuma migration.

## 11. Testes

| O que prova | Onde |
|---|---|
| O pixel vira instante, preso ao passo da densidade | `grade.test.ts` |
| Arrastar para cima devolve o período na ordem certa | `grade.test.ts` |
| Arrastar num vazio abre a escolha, com o período do gesto | `Gesto.test.tsx` |
| Marcar manda `origem: PAINEL` e o início arrastado | `Gesto.test.tsx` |
| A duração é do serviço, não do arrasto — e a tela mostra o fim | `Gesto.test.tsx` |
| Repetir cria série, e os dias que não couberam aparecem | `Gesto.test.tsx` |
| Pôr um dia recusado na fila | `Gesto.test.tsx` |
| Bloquear manda o período inteiro do arrasto | `Gesto.test.tsx` |
| Bloqueio sobre atendimento mostra a frase do servidor | `Gesto.test.tsx` |
| Arrastar a tarja reagenda com a versão lida | `Gesto.test.tsx` |
| Soltar no mesmo lugar não escreve nada | `Gesto.test.tsx` |
| Recusa no arrasto mostra o motivo | `Gesto.test.tsx` |
| Capacidades sem o campo de permissões não derrubam a tela | `capacidades.test.ts` |
| Confirmar, cancelar com motivo e falta, do detalhe | `Gesto.test.tsx` |
| Quem não escreve agenda não vê gesto nenhum | `Gesto.test.tsx` |
| Sem violação estrutural de acessibilidade | `Gesto.test.tsx` |

## 12. O que foi construído

| Peça | Onde |
|---|---|
| Pixel → instante, preso ao passo da densidade | `grade.ts` · `instanteDoPixel`, `periodoDoArrasto` |
| O arrasto no vazio, e o que ele desenha | `Agenda.tsx` · `.agenda-selecao` |
| O painel que pergunta "o quê" | `Gesto.tsx` · `PainelDoGesto` |
| Marcar, com recorrência e com o que não coube | `Gesto.tsx` · `Marcar`, `DepoisDaSerie` |
| Bloquear, e tirar o bloqueio com um clique | `Gesto.tsx` · `Bloquear` · `Agenda.tsx` |
| Confirmar, cancelar com motivo e registrar falta | `Agenda.tsx` · `AcoesDoAtendimento` |
| Arrastar a tarja dentro da coluna | `Agenda.tsx` · `Tarja` |
| Os ganchos de escrita da agenda | `src/api/agenda.ts` |

**Testes.** 18 de tela (`Gesto.test.tsx`), 11 de geometria (`grade.test.ts`) e 3 de permissão
(`capacidades.test.ts`), incluindo acessibilidade com axe.

**Rodada de mutação: 13 mutações, 13 mortas.** Uma sobreviveu na primeira passada por um defeito
do teste, não do código: `fireEvent` é síncrono e a escrita sai num microtask, então a asserção
"não escreveu nada" passava **mesmo quando escrevia**. O mesmo engano já tinha aparecido no teste
do reagendamento, quinze minutos antes.

**Um defeito real, achado pelo teste da grade que já existia:** `pode()` derrubava a tela inteira
quando as capacidades chegavam **sem o campo de permissões** — `capacidades?.permissoes.includes`
tem o `?.` no primeiro ponto e não no segundo. É a mesma lição de `grupos` na casca, registrada em
`17-checklist-de-frontend.md`: *um campo que o front usa para decidir o que desenhar não pode
derrubar o que ele desenha*. A casca tinha aprendido; `pode` não.

**E um de posicionamento, achado na prévia:** o painel do gesto ancorava na página, e não na
grade — `.agenda-corpo` era `static`. Ele subia para o canto superior da tela, longe do horário
que o gesto acabou de desenhar.

## 13. Pendências

- [ ] **O destino não avisa antes que não cabe.** A grade tem os agendamentos do dia em memória e
      poderia pintar o destino em vermelho durante o arrasto (R-UX-06); hoje a recusa só chega do
      servidor. É barato e não entrou aqui.
- [ ] **Arrastar entre colunas** não move de profissional. O gesto existe dentro da coluna; levar
      a tarja para outra pessoa exige medir a posição das colunas, e o jsdom não tem layout — um
      arrasto que move atendimento sem teste automático não entra. Precisa de teste em navegador
      de verdade, e é rotina própria.
- [ ] **Sem atalhos de teclado** (R-UX-02): o fluxo inteiro deveria funcionar sem mouse, e o
      arrasto não tem equivalente de teclado. Hoje marcar sem mouse não é possível.
- [ ] **Sem barra de comandas abertas** (R-UX-07) — depende de `atendimento`.
- [ ] **`agenda:read:own` e `:write:own` não filtram**: a grade mostra todo mundo para quem só
      deveria ver a própria agenda. É a pendência que atravessa a fase inteira, e depende do
      vínculo `usuario` → `profissional`.
- [ ] **Bloqueio recorrente não existe** — "toda quarta das 12h às 13h" ainda são 52 bloqueios.
      Pendência de RT-AGD-007, e parente da recorrência: provavelmente a mesma máquina.
- [ ] **Alterar a série não existe**: mudar o horário de "toda quinta" é encerrar e criar outra.
- [ ] **O bloqueio de recurso não tem gesto.** A grade é de profissionais; bloquear o lavatório
      exige uma coluna de recursos que a grade ainda não desenha.
