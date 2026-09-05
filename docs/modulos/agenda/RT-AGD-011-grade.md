---
id: RT-AGD-011
titulo: Grade de agenda (front)
modulo: agenda
fase: 1
perfil: completo
status: implementado
depende_de: [RT-AGD-001, RT-AGD-002, RT-AGD-007]
permissoes: [agenda:read:all, agenda:read:own]
eventos: []
regras: [RN-AGD-010, RN-CAT-001]
atualizado_em: 2026-09-05
---

# RT-AGD-011 — Grade de agenda

## 1. Objetivo

A tela em que a recepção passa o dia. Uma coluna por profissional, o dia na vertical, e a
informação que decide um encaixe visível sem clicar.

## 2. A tarja é o BLOCO, não o agendamento

É a decisão que distingue esta grade de qualquer calendário.

A coloração da Marilda ocupa três tarjas: aplica das 10h às 10h30, **hachura sem texto** das 10h30
às 11h10, e finaliza das 11h10 às 11h40. A hachura é a química agindo — a cliente está no salão e a
profissional está **livre**.

E é por isso que o corte da Ju às 10h40 aparece por cima dela: o encaixe existe, e a grade mostra
que existe. Uma tarja única de 100 minutos esconderia os 40 minutos vendáveis — que é exatamente o
que este sistema inteiro foi construído para não fazer.

## 3. A carga conta minutos da profissional, não duração

O cabeçalho de cada coluna traz `2h15`, e são os minutos **dela**.

Numa coloração de 100 minutos, 40 são pausa. Contar os 100 diria que a agenda está cheia quando
ainda cabe um corte no meio — e este é o número que decide se dá para encaixar mais um. Foi o
segundo achado mais sério da crítica de design.

## 4. A linha do agora, e o selo que a desambigua

Sem a linha, um calendário é uma tabela — o achado mais sério da crítica.

Ela é vermelha, e **a falta também**: `--status-falta` é a cor dos dois. Uma linha vermelha
atravessando a grade teria dois significados possíveis, e pior: ela cruza justamente os blocos de
falta. O canvas resolvia com um **selo com a hora** na régua, que eu tinha pulado — ele diz qual dos
dois é. Um marcador por coluna repetiria a mesma informação quatro vezes numa tela já densa; o selo
fica só na régua.

A linha não aparece na agenda de outro dia. Sem isso, quem abre amanhã vê uma linha grudada no topo
dizendo que "agora" é 8h de um dia que não é hoje.

## 5. Fora da jornada é escuro, não branco

Coluna vazia e clara **parece disponível**. A recepção ofereceria um horário em que a profissional
não trabalha, e descobriria o erro só quando o cadastro recusasse.

A profissional que não trabalha no dia tem a coluna inteira escurecida — lê como "hoje não" sem
precisar de texto.

## 6. Duas medições e um clique falso

O que só apareceu **no navegador**, como em RT-CLI-006:

**`opacity` quebra o contraste em silêncio.** O bloco cancelado usava `opacity: 0.65`, e a medição
continuava lendo a cor *declarada* — dizia 4.67 e aprovava. Opacidade no container compõe o texto
com o fundo e derruba o contraste real: é um jeito de reprovar em AA passando na verificação. Virou
forma: borda tracejada, sem preenchimento, texto riscado.

**Fundo padronado não tem UM contraste.** O texto do bloqueio em `--tinta-fraca` media 4.67 contra o
papel — mas a hachura alterna `--papel-afundado` e `--regua`, e sobre a listra escura cai abaixo de
AA. Fundo com padrão tem o contraste do **pior pixel**, e é esse que vale. Passou a `--tinta` cheia.

**Um botão que não faz nada é pior que um bloco que não é botão.** As tarjas eram `<button>` sem
`onClick` — affordance falsa. Ganharam o painel de detalhe, que mostra o que não cabe em 20px de
altura: nome inteiro, status por extenso, e **duração separada de "da agenda"** — 1h40 e 1h00 na
mesma coloração, que é a diferença invisível na grade.

Painel lateral e não diálogo: a pergunta seguinte é sempre *"e o que tem em volta?"*.

## 7. Uma chamada, não quatro

`GET /api/v1/agendamentos/dia` traz profissionais, jornadas, agendamentos, blocos, bloqueios e nomes.

A recepção troca de dia a cada poucos segundos, e cada troca que dispara quatro requisições produz
quatro momentos em que a tela está pela metade — colunas sem blocos, blocos sem nome. São seis
consultas por dentro, **nenhuma por profissional nem por atendimento**.

Para os nomes, `ClienteApi` ganhou `nomesDe(Collection)`: só o nome, numa consulta. É a diferença
para `porId` — a grade escreve algo na tarja e nada mais, e telefone e ficha não têm o que fazer
numa tela que mostra trinta pessoas de uma vez.

## 8. O cálculo mora fora do React

`grade.ts` é função pura: converter instante em pixel é a parte que erra **silenciosamente** — um
bloco meia hora fora do lugar não quebra nada, só mostra a coisa errada.

E converte no **fuso do salão**, não no do navegador: sem isso, um salão em Manaus veria a agenda
uma hora deslocada, e ninguém percebe porque a tela continua coerente consigo mesma.

## 9. Testes

- [x] `grade.test.ts` — 15: **converte no fuso do salão, não no do navegador** · a faixa abre uma
      hora antes e depois · o fim na hora cheia não estende · bloco curto continua clicável ·
      **a linha do agora só no dia de hoje** · não aparece fora da faixa · **a carga conta minutos
      da profissional**
- [x] `Agenda.test.tsx` — 10: coluna por profissional com apelido · **a pausa não vira tarja com
      nome** · **a carga é 1h00 numa coloração de 1h40** · a linha do agora · não aparece em outro
      dia · **o cancelado fica riscado e não conta na carga** · o bloqueio mostra o motivo · salão
      sem profissional explica · erro não deixa tela em branco · **o clique mostra o que não cabe**
      · status por extenso

## 10. Pendências

- [ ] **Não se arrasta.** RT-AGD-003 existe no backend e a grade não a chama — arrastar é o gesto
      central da tela e é o maior buraco desta rotina. Precisa de estado otimista com rollback
      visível (o sistema de design tem regra própria para isso) e de destino em vermelho antes de
      soltar (R-UX-06)
- [ ] **Não se marca nada pela grade.** Clicar num vazio deveria abrir a marcação; hoje o painel só
      lê. RT-AGD-002 está pronta do lado do servidor
- [ ] **Confirmar e cancelar não estão no painel** — são os dois cliques mais frequentes da
      recepção, e cada um precisa de confirmação e de atualização otimista
- [ ] **Sem barra de comandas abertas** (R-UX-07) — o canvas a tem, e ela depende de `atendimento`
- [ ] **Sem atalhos de teclado** (R-UX-02): o fluxo inteiro deveria funcionar sem mouse
- [ ] **`agenda:read:own` não filtra** — a grade mostra todo mundo para quem só deveria ver a
      própria agenda. É a mesma pendência que atravessa a fase inteira
- [ ] **`design:accessibility-review` completo continua pendente.** Contraste foi medido, foco tem
      contorno, `prefers-reduced-motion` está tratado — mas navegação por teclado numa grade de
      posicionamento absoluto merece a auditoria formal
