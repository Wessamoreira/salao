---
id: RT-INF-019
titulo: Duas fontes — as mesmas do Ecoa, e a monoespaçada sai
modulo: infra
fase: 7
perfil: leve
status: implementado
depende_de: [RT-INF-010, RT-INF-017]
permissoes: []
eventos: []
regras: []
atualizado_em: 2026-11-15
---

# RT-INF-019 — Duas fontes

## 1. Objetivo

Uma família no texto, outra na identidade, e nenhuma terceira. Pedido do dono: *"deixa a mesma
fonte do projeto de inglês, não quero várias fontes"* — o Ecoa, outro produto dele, usa Atkinson
Hyperlegible Next no texto e Bricolage Grotesque nos títulos.

## 2. Contexto — medido

| Achado | Medido |
|---|---|
| **Três desenhos de letra na mesma tela** | Bodoni Moda (didone) nos totais, Archivo no texto, e a monoespaçada do sistema (`ui-monospace`) na chave do segundo fator, no código de barras e nos exemplos da busca de clientes |
| **A monoespaçada não era a mesma em toda máquina** | `ui-monospace, "SF Mono", Menlo` é pilha de sistema — o defeito que `RT-INF-010` corrigiu para as outras duas famílias e deixou nesta |

## 3. Decisões

**Atkinson Hyperlegible Next em todo o texto.** Desenhada pelo Braille Institute para baixa
visão: cada letra que se confunde com outra (0/O, 1/l/I) tem desenho próprio. Era exatamente o
serviço da monoespaçada na chave do segundo fator e no EAN — por isso ela sai sem deixar falta.
`--fonte-mono` passa a ser `var(--fonte)`: as nove regras que a usam continuam valendo.

**Bricolage Grotesque na identidade e nos totais.** O gesto continua num lugar só, como a didone
antes. A diferença é que o total da comanda não troca de família de letra no meio da conta.

**Numeral tabular, medido antes da troca.** As duas têm `tnum`: dez "1" e dez "0" com a mesma
largura nas duas famílias. A grade de horários continua alinhando em coluna.

**O zero cortado é do desenho, e fica.** A Atkinson corta o zero no proporcional e no tabular
(é o "0 que não é O" da fonte), e o Ecoa mostra o mesmo zero. Trocar só os números por outra
família traria de volta a terceira fonte que este pedido tirou.

**Mesma técnica de carga.** Variáveis (peso é desenho, não negrito sintetizado), auto-hospedadas,
só o arquivo latino: **75 KB pelas duas** (34 + 41), contra 81 KB das anteriores.

## 15. Testes obrigatórios

| Verificação | Resultado |
|---|---|
| Só duas famílias na tela | Prévia da comanda: `Atkinson Hyperlegible Next Variable + Bricolage Grotesque Variable`, nenhuma outra com texto |
| Numeral tabular | 10×"1" e 10×"0" com a mesma largura nas duas famílias |
| Peso da primeira visita | 75 KB de fonte no app shell (antes 81 KB) |
| Nenhuma regressão | `npm test` e `npm run typecheck` |
