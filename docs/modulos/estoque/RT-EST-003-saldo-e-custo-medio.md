---
id: RT-EST-003
titulo: Saldo e custo médio ponderado (projeção, não coluna)
modulo: estoque
fase: 3
perfil: completo
status: implementado
depende_de: [RT-EST-002]
permissoes: [estoque:read]
eventos: []
regras: [RN-EST-009, RN-EST-010]
atualizado_em: 2026-09-05
---

# RT-EST-003 — Saldo e custo médio

## 1. Objetivo

Responder *"quantos eu tenho e quanto isso vale"*. É o número que transforma **"vendi por 45"** em
**"ganhei 23"**.

## 2. Projeção, e o subtítulo do plano é a regra

RT-EST-002 já provou a disciplina no nível do lote; esta rotina a leva ao produto. Saldo, custo
médio e valor em estoque são calculados dos movimentos a cada consulta — nenhum dos três é coluna
que alguém atualiza.

O que se compra com isso já foi dito em RN-EST-006 e vale de novo aqui, ampliado: **não existe
"recalcular o estoque"**. Não há job noturno, não há script de correção, não há a conversa de
*"o saldo do sistema está errado, precisa rodar aquela rotina"* — que é a conversa que todo sistema
de estoque com coluna de saldo acaba tendo.

## 3. Só quem tem unidade pondera

O custo médio é a média ponderada dos lotes com **saldo positivo**.

- **Lote zerado sai da média.** Ele não tem unidade nenhuma para ponderar, e incluí-lo puxaria a
  média para o custo de mercadoria que já saiu. A média é do que está *na prateleira*, não do que
  já passou por ela.
- **Lote negativo também sai.** Peso negativo *subtrairia* valor de um estoque que existe, e a
  média poderia até ficar negativa.

A **quantidade**, essa soma tudo — inclusive o negativo. Esconder um saldo negativo seria esconder
o erro que precisa ser corrigido (RT-EST-005), e um estoque que nunca mostra número estranho é um
estoque em que ninguém confia.

## 4. Sem estoque, custo médio é nulo

E nulo **não é zero**. Zero significaria *"tenho unidades que não custaram nada"* — que é o caso
real da amostra do fornecedor, e o sistema aceita custo zero justamente por isso. Sem estoque não
há média a tirar.

É a mesma distinção de `ServicoApi.precoEm` (RN-ATD-005) e de `taxa_prevista` em RT-FIN-001: a
ausência tem significado próprio, e colapsá-la em zero apaga esse significado num relatório.

## 5. Duas telas, dois filtros

| Filtro | Tela |
|---|---|
| `somenteComEstoque=false` (padrão) | **reposição** — o que interessa é justamente o que zerou |
| `somenteComEstoque=true` | inventário do que há |

O padrão é o contrário do que a intuição sugere, e de propósito: uma lista de estoque que esconde o
que acabou não serve para comprar.

## 6. Testes

- [x] `SaldoEcustoMedioIT` — 8: **o custo médio pondera pelos lotes com unidades** (10 a 20 + 5 a
      26 = 22) · **lote esvaziado sai da média** · saída parcial mantém o peso que sobrou ·
      **sem estoque o custo médio é nulo, e nulo não é zero** · amostra de graça entra com custo
      zero · **o saldo negativo aparece** · a lista traz o que está zerado · um salão não vê o
      saldo do outro
- [x] `ProdutosWebIT` — 11 (2 novos): o saldo e o custo médio saem prontos do servidor ·
      **`/saldos` é caminho, não uuid**, e traz o que está zerado

## 7. Pendências

- [ ] **O custo médio ainda não chega à comanda.** `item_comanda.custo_medio_unitario` continua
      nulo: quem o preenche é a baixa (RT-EST-004), com o custo dos lotes de onde as unidades
      saíram — que não é o mesmo número que este, e é de propósito
- [ ] **Não há margem em lugar nenhum.** Custo existe, preço existe, e nenhuma consulta os cruza
- [ ] **Nada impede vender sem ter.** O saldo é calculável e ninguém o consulta antes de lançar um
      produto na comanda (RT-ATD-003). Quem vai barrar é a baixa, no fechamento
- [ ] **Sem estoque mínimo.** A lista de reposição mostra o que zerou, não o que está *quase*
      zerando — isso é RT-EST-008
- [ ] **A projeção varre todos os lotes do produto.** Aceitável em dezenas de lotes; num salão que
      nunca faz inventário e acumula lotes zerados por anos, vira varredura. O índice ajuda e não
      resolve — o que resolve é o inventário (RT-EST-006) fechando lotes velhos
- [ ] **Não há tela.** As rotas existem e o front não as usa
