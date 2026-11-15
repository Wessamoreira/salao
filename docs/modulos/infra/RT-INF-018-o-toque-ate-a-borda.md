---
id: RT-INF-018
titulo: O toque até a borda — abas inteiras, tabela que vira cartão, unidade dentro da opção
modulo: infra
fase: 7
perfil: leve
status: implementado
depende_de: [RT-INF-013, RT-INF-017]
permissoes: []
eventos: []
regras: [R-UX-24, R-UX-25]
atualizado_em: 2026-11-15
---

# RT-INF-018 — O toque até a borda

## 1. Objetivo

No celular, todo toque que parece acertar um controle acerta, e nenhuma lista pede para ser
arrastada de lado. O dono pediu o acabamento de toque de outro projeto dele (Ecoa), onde os dois
defeitos abaixo foram encontrados e corrigidos — e o Nexo tem os mesmos.

## 2. Contexto — medido

Medido nas prévias (`frontend/preview/`) em 390px, com a barra de gestos do iPhone simulada
(34px de `safe-area-inset-bottom`).

| Achado | Medido |
|---|---|
| **A faixa da barra de gestos não é de ninguém** | `.casca-abas` reserva os 34px como `padding` do contêiner: ficam **fora** dos botões. A barra mede 90px (56 de botões + 34 vazios) e só **10 de 20** toques de teste acertam a aba — tocar abaixo do rótulo ou perto da borda não faz nada |
| **As abas parecem flutuar** | Com os 34px inteiros vazios embaixo, ícone e rótulo ficam altos demais em relação à borda |
| **Três telas pedem rolagem lateral no toque** | Itens da comanda (`cmd-itens-rolagem`), fila (`fil-rolagem`) e preços (`prc-rolagem`): tabela de 5 a 7 colunas numa tela de 390px |
| **"min" parece uma opção** | No seletor de minutos por linha da agenda, abaixo de 820px os botões esticam e o rótulo fica do lado como uma quarta casa, com a mesma altura e o mesmo fundo |

Três telas também pedem rolagem lateral e **ficam como estão**, cada uma por um motivo:

- **Agenda** (`agenda-corpo`): colunas por profissional com rolagem horizontal é regra
  (`10-usabilidade.md`), não defeito.
- **Barra de comandas abertas** (`cmd-barra-lista`): é uma fila de cartões que se percorre de
  lado de propósito, com o próximo cartão aparecendo cortado como convite.
- **Conciliação** (`cnc-rolagem`, três tabelas): cinco tabelas e 52 células, com o painel de
  decisão dimensionado pela largura visível da rolagem (RT-FIN-011). Se conciliação se faz no
  celular é decisão do dono — registrada em `13-perguntas-em-aberto.md`, não assumida aqui.

## 3. Decisões

**Cada aba vai até a borda da tela (R-UX-24).** A faixa da barra de gestos deixa de ser
`padding` do contêiner e passa a ser `padding` **de cada aba**: a coluna inteira é alvo, do traço
de cima até o vidro. Da faixa, só se reserva o que o indicador de gestos precisa — a faixa menos
`--e-3` —, e o conteúdo encosta onde o polegar está. Sem barra de gestos a conta dá zero e nada
muda.

**Uma variável para a altura da barra inteira.** `--abas-total` (botões + faixa) é declarada uma
vez na casca. Quem fica acima das abas — o conteúdo, o detalhe da agenda, o pé da comanda — usa
ela, em vez de somar a faixa por conta própria em três arquivos. Fora da casca (prévia) ela não
existe e o fallback é a faixa sozinha, como antes.

**Tabela de registros vira cartão no toque (R-UX-25).** Abaixo de 820px, a linha vira um cartão: o
nome do registro em cima, cada dado com a etiqueta da coluna, as ações numa linha própria. A
etiqueta usa o desenho de etiqueta do sistema (versalete 10px, `--tinta-fraca`, 3b do
`16-design-system.md`) — é o mesmo papel do cabeçalho da coluna, só que ao lado do dado.

Uma classe compartilhada (`tabela-cartoes`) em `componentes.css`, e não três versões locais: é a
lição de `RT-INF-017`, onde o campo existia vinte vezes.

**A tabela continua tabela para quem não enxerga.** Mudar o `display` de `table`/`tr`/`td` faz o
Chrome e o Safari descartarem a semântica de tabela — o leitor de tela deixa de anunciar "linha 2
de 5, coluna Total". Por isso a marcação ganha os papéis explícitos (`role="table"`, `row`,
`columnheader`, `rowheader`, `cell`), que sobrevivem à troca de `display`. O cabeçalho continua
no DOM, escondido só da vista.

**A unidade entra na opção.** No toque, cada botão do seletor diz "15 min", e o rótulo solto sai.
No desktop o desenho de antes continua: lá os botões não esticam e o rótulo lê como rótulo.

## 4. Fora do escopo

- Reorganizar a comanda no celular (formulário de lançar ocupando a tela antes dos itens) e a
  agenda no celular (duas colunas visíveis). Mudam o fluxo de trabalho, não o acabamento: são
  decisão do dono, em rotina própria.
- O visual do Ecoa. O Nexo tem direção própria e mais restritiva (`16-design-system.md`, "O que
  este projeto não faz"); o que veio de lá é o comportamento de toque, não a aparência.

## 15. Testes obrigatórios

| Verificação | Como |
|---|---|
| Toque na coluna inteira da aba, inclusive na faixa da barra de gestos | Prévia `sistema.html` em 390px com faixa de 34px simulada: ícone, rótulo, abaixo do rótulo e borda da tela, nas cinco abas |
| Nada acima das abas fica coberto | Pé da comanda e detalhe da agenda medidos contra o topo da barra |
| Nenhuma rolagem lateral nas três tabelas no toque | Varredura de `overflow-x` em 360px e 390px |
| A tabela continua tabela no leitor de tela | `deveSerAcessivel` (axe) nos testes das três telas |
| Nenhuma regressão | `npm test` e `npm run typecheck` |

## 16. Resultado — medido

Prévias em 360px e 390px; barra de gestos do iPhone simulada pela folga que ela produz (22px).

| Verificação | Antes | Depois |
|---|---|---|
| Barra de abas com barra de gestos | 90px · **10/20** toques acertam | 78px · **20/20** |
| Barra de abas sem barra de gestos | 56px · 20/20 | 56px · 20/20 (inalterada) |
| Fim da folha × topo das abas | — | encostados (766px / 766px) |
| Itens da comanda, fila, preços em 360px | rolagem lateral | nenhuma |
| Seletor da agenda no toque | "min · 15 · 30 · 60" | "15 min · 30 min · 60 min" |
| Desktop: agenda, comanda, preços | — | inalterados (tabela, rótulo, célula vazia) |
| `npm test` | — | 31 arquivos · 386 testes, axe incluso |
| `npm run typecheck` | — | sem erro |

**Crítica aplicada antes da entrega.** A primeira versão punha em negrito a primeira célula
inteira do cartão — e com ela o texto de apoio que mora junto ("Serviço · executou 40%", a
observação da fila, quem decidiu o preço). O peso voltou para a tela. E "Motivo da recusa —"
aparecia em toda decisão aprovada: no cartão, célula vazia é ruído, e passou a sair.

**Achado fora do escopo, não corrigido aqui.** Em 360px, o resumo da comanda (`.cmd-totais`)
não cabe: "BRUTO R$ 215,00" vaza para fora do painel pela esquerda. A área está sendo refeita em
`RT-ATD-009`; a correção vai junto dela, e não por baixo.
