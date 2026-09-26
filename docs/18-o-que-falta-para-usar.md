# O que falta para o sistema ser usado de verdade

**Levantamento de 23/09/2026.** Não é plano novo: é a leitura honesta do que existe hoje contra o
que um salão precisa para abrir a porta de manhã e trabalhar o dia inteiro sem sair da tela.

---

## Dois enganos, e o segundo era meu

### O da prévia

A prévia da casca (`frontend/preview/sistema.tsx`, servida em `/preview/sistema.html`) desenha
**Agenda** e **Atendimento** como "Em breve". Aquilo não é o produto: é o andaime da prévia, que
existe para olhar a **casca** — menu, topo, busca — sem carregar as telas que têm prévia própria
(`previa-agenda`, `previa-comandas`).

No aplicativo de verdade (`frontend/src/App.tsx`), só **duas** rotas mostram "Em breve":
`/estoque` e `/financeiro`.

Lição que fica: **prévia que finge ser o sistema mente sobre o sistema.** Uma prévia parcial
precisa dizer que é parcial — do contrário, o dono abre a porta 5174 e conclui que o produto
regrediu.

### O do "Em breve"

As telas de Estoque e Financeiro diziam: *"Esta área chega com a rotina RT-EST-001"* e *"com a
rotina RT-FIN-004"*. As duas rotinas estavam **implementadas havia semanas** — RT-EST-001 é o
cadastro de produto, RT-FIN-004 é o extrato do profissional, e as duas passaram por teste de
integração. Quem lia aquilo concluía, corretamente pelo texto e erradamente pelo fato, que o
estoque e o financeiro inteiros estavam por fazer.

Nomear a rotina errada é pior do que não nomear nenhuma: manda procurar no lugar errado.

Corrigido em 24/09/2026. As duas telas agora dizem o que o servidor já faz, **o que dá para
fazer hoje por outro caminho** — produto se vende na comanda e a baixa FEFO acontece sozinha; o
dinheiro entra e se concilia — e qual rotina traz a tela: RT-EST-013 e RT-FIN-013, na fase 7.

---

## O que já está de pé

Oito telas, em uso contra o backend real:

| Tela | Rota | Cobre |
|---|---|---|
| Agenda | `/agenda` | grade do dia, tarjas, pausa, linha do agora, carga, régua de densidade |
| Fila de espera | `/fila` | vagas abertas, ordem de chamada, motivo do pulo, pôr na fila |
| Atendimento | `/comandas` | comanda, itens, desconto, pagamento, fechamento |
| Clientes | `/clientes` | busca por nome/telefone, ficha, consentimentos, duplicados |
| Revisão do agente | `/agente` | trilha das ações da IA, recusas, gravações |
| Conciliação | `/conciliacao` | extrato do adquirente, divergências, resolução à mão |
| Preços | `/precos` | sugestões, aprovação e recusa com motivo |
| Catálogo | `/catalogo` | serviço com receita de blocos, preço com vigência, equipamentos *(24/09)* |
| Configuração | `/config` | identidade, atendimento, comissão e fechamento |
| Painel de parede | `/painel` | fila do dia para a tela pendurada |

Mais a porta do sistema (entrar, segundo fator) e a casca (menu, `⌘K`, estado da conexão).

---

## O buraco, medido

O backend tem **145 endpoints** em 34 controladores. Na medição de 23/09:

- **38 não tinham tela nenhuma;**
- **25 tinham só a leitura de lista** que alguma tela usa para preencher um seletor — criar,
  editar e desativar não existiam em lugar nenhum.

**63 de 145 (43%) do que o servidor sabia fazer não tinha por onde ser feito.**

Cada tela da fase 7 derruba esse número; ele é o placar:

| Depois de | O que passou a ter tela | Sem tela |
|---|---|---|
| RT-CAT-005 · 24/09 | `/v1/servicos`, `/v1/recursos` | 53 de 145 (37%) |
| RT-EQP-005 · 24/09 | `/v1/profissionais` (+ editar), `/v1/bloqueios-de-jornada` | 41 de 146 (28%) |
| RT-IAM-009 · 25/09 | `/v1/usuarios` (+ editar e senha), `/v1/perfis`, `/v1/me/senha` | **34 de 150 (23%)** |

O total sobe porque as telas acharam endpoints que faltavam: editar serviço, editar profissional,
editar usuário, definir senha e o catálogo de perfis. Buraco que só aparece quando alguém tenta
usar.

Não é código faltando no servidor: é tela faltando. Cada linha abaixo tem endpoint testado,
regra registrada e migration aplicada.

---

## Por ordem de bloqueio

### 1. Cadastros — sem eles o sistema não sai do zero

| Falta | Endpoints prontos | Sem isso |
|---|---|---|
| ~~Profissional, jornada semanal, bloqueio de jornada, habilidades~~ | `/v1/profissionais` (9), `/v1/bloqueios-de-jornada` (4) | **Feito em 24/09** — RT-EQP-005. A agenda deixou de abrir vazia |
| ~~Serviço com blocos, preço com vigência~~ | `/v1/servicos` (7) | **Feito em 24/09** — RT-CAT-005 |
| ~~Recurso (lavatório, cadeira, secador)~~ | `/v1/recursos` (4) | **Feito em 24/09** — RT-CAT-005 |
| ~~Duração por profissional~~ | `/v1/profissionais` | **Feito em 24/09** — mora nas habilidades, dentro da tela da equipe |
| ~~Usuário e login~~ | `/v1/usuarios` (8), `/v1/perfis` (1) | **Feito em 25/09** — RT-IAM-009. Antes, criar acesso para a recepção exigia chamada HTTP na mão |

Este bloco era o que separava "o sistema roda" de "o salão consegue começar", e **está fechado**:
desde 25/09 dá para abrir um salão novo — cardápio, equipe com jornada e os logins de quem
trabalha — sem sair da tela.

### 2. Financeiro — o dinheiro entra e não fecha

| Falta | Endpoints prontos |
|---|---|
| Extrato do profissional | `/v1/extratos` (1) |
| Vale | `/v1/vales` (3) |
| Fechamento e repasse | `/v1/fechamentos` (4) |
| Lançamento avulso e estorno | `/v1/lancamentos` (1) |
| Regra de comissão por serviço/profissional | `/v1/regras-de-comissao` (4) |

O pagamento da cliente já é registrado na comanda. O que não existe é o outro lado: quanto cada
profissional tem a receber, o que foi adiantado, e o fechamento do mês.

### 3. Estoque — o produto sai e ninguém dá baixa pela tela

`/v1/produtos` tem 13 endpoints; a única coisa que o front faz é listar o que é vendável para a
comanda. Cadastro, entrada de lote, saldo, custo médio, inventário, queima de vencimento e código
de barras não têm tela.

### 4. Agenda — três operações que a grade promete e não entrega

- **Bloqueio de grade** (`/v1/bloqueios-de-agenda`, 3): "vou sair uma hora" não tem como ser
  registrado. R-UX-10 pede arraste na grade.
- **Recorrência** (`/v1/recorrencias`, 3): a série existe no servidor e só é alcançada pela fila.
- **Remarcar arrastando**: a grade é leitura; reagendar exige outra via.

### 5. Canal — nada sai do sistema

Não há provedor de WhatsApp configurado. Consequência, hoje, em cadeia:

- lembrete de agendamento e comprovante ficam na fila, marcados `SEM_PROVEDOR` (RT-NOT-001);
- o agente conversacional não recebe nem responde mensagem (RT-CNV-001);
- a chamada da fila de espera é feita pelo telefone, na mão, pela recepção.

Falta **decisão sua** (pergunta 14 de `13-perguntas-em-aberto.md`): qual provedor de WhatsApp,
qual provedor de LLM e a que preço, e qual provedor de transcrição de áudio. Sem isso, a Fase 4
inteira fica sendo um motor sem combustível.

### 6. Permissão por profissional

`agenda:read:own` e `agenda:write:own` não filtram nada: quem entra com essas permissões enxerga
e mexe na agenda de todo mundo. Enquanto for assim, **não dá para dar login a cada profissional**
— só à recepção e ao dono. É a pendência mais repetida da fase 1 (aparece em cinco rotinas).

### 7. Infra — o que falta para rodar fora da sua máquina

`RT-INF-009` está `implementado-parcial`, com 5 pendências: máquina de homologação, backup fora
da máquina, e o restante do caminho de deploy.

---

## Ordem sugerida

1. **Cadastros.** ~~Catálogo e recursos~~ (feitos em 24/09) → **equipe** → **usuários**. É o
   único bloco que impede o resto de ser usado, e é pré-requisito do próprio teste de aceitação:
   sem profissional com jornada, a agenda abre vazia mesmo com o cardápio pronto.
2. **Financeiro** (extrato → vale → fechamento). É o que o dono olha toda semana.
3. **Estoque** (produto → entrada → saldo).
4. **Agenda: bloqueio e recorrência.**
5. **Canal de WhatsApp**, quando as três decisões da pergunta 14 estiverem tomadas.
6. **Permissão `own`**, antes de existir login de profissional.

---

## O que este levantamento não diz

Que o que está pronto está errado. As oito telas de pé têm teste, rodada de mutação e auditoria
de acessibilidade a cada commit. O problema não é qualidade do que existe — é **cobertura**: o
servidor sabe fazer quase tudo, e quase metade disso ainda não tem por onde ser feito.
