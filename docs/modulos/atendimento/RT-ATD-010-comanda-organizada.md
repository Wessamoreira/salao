---
id: RT-ATD-010
titulo: A comanda organizada — totais como nota, e a conta antes do formulário no toque
modulo: atendimento
fase: 7
perfil: leve
status: implementado
depende_de: [RT-ATD-009, RT-INF-018]
permissoes: []
eventos: []
regras: []
atualizado_em: 2026-11-15
---

# RT-ATD-010 — A comanda organizada

## 1. Objetivo

O dono pediu a comanda *"mais bem organizada"*. Sem mudar regra nenhuma: quem soma continua sendo
o servidor (RN-ATD-008) e o que `RT-ATD-009` garantiu (dinheiro sempre à vista, pé fixo no toque)
fica como está.

## 2. Contexto — medido

| Defeito | Medido |
|---|---|
| **No celular, o formulário vinha antes da conta** | Serviço, quem executou, bipe, produto, quantidade e dois botões: os itens começavam abaixo de uma tela inteira de formulário. Quem abre a comanda para conferir o que já foi lançado rolava por ele |
| **Totais desalinhados** | Bruto, desconto e total em três colunas lado a lado, com o rótulo "TOTAL" mais alto que os outros dois (o valor de 30px empurrava o rótulo) |
| **Totais fora do painel em 360px** | "BRUTO R$ 215,00" vazava pela esquerda do painel branco (achado em `RT-INF-018`) |
| **"Bipar produto" fora da linha** | O leitor de código vem do estoque com 8px entre rótulo e campo; os grupos da comanda têm 4px. Com a linha alinhada pela base, o rótulo subia |

## 3. Decisões

**Totais como uma nota.** Rótulo à esquerda, valor à direita, uma linha por número, e um traço
antes do total — o mesmo desenho do resumo da coluna do dinheiro, para as duas metades se lerem
como um documento só. Largura de até 360px, encostada à direita: cabe em qualquer tela.

**No toque, o formulário fica atrás de "Adicionar à comanda".** Depois do cabeçalho vem a conta.
O botão diz o estado (`aria-expanded`) e controla o formulário (`aria-controls`).

- **Comanda vazia abre com ele aberto:** sem conta para mostrar, o próximo passo é lançar.
- **Recolher é só CSS:** o formulário continua montado e não perde o que se estava escolhendo.
- **No desktop o botão não existe** e o formulário fica sempre à vista, como antes — lá ele cabe
  ao lado da tabela.

**O leitor de código entra na linha com o espaçamento da comanda** (4px entre rótulo e campo).

## 4. Fora do escopo

R-UX-08 (serviço, produto, desconto e pagamento na mesma tela) continua inteira: nada saiu da
tela, nada virou diálogo.

## 15. Testes obrigatórios

| Verificação | Resultado |
|---|---|
| Com itens, começa recolhido e alterna | `Comandas.test.tsx` — "com itens, começa recolhido, diz o estado e alterna" |
| Comanda vazia abre com o formulário à vista | `Comandas.test.tsx` — "comanda vazia já abre com o formulário à vista" |
| Os 17 testes anteriores | Passam sem mudar o que verificam (19 no total) |
| Acessibilidade | `deveSerAcessivel` (axe) na comanda aberta |
| Celular, 360px | Itens começam a 256px do topo (antes: abaixo de uma tela de formulário); totais dentro do painel; nenhuma rolagem lateral |
| Desktop | Botão ausente (`display: none`); formulário, tabela e coluna do dinheiro como antes |
