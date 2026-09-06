---
id: RT-EST-002
titulo: Entrada de lote com custo e validade
modulo: estoque
fase: 3
perfil: completo
status: implementado
depende_de: [RT-EST-001]
permissoes: [estoque:read, estoque:write]
eventos: []
regras: [RN-EST-006, RN-EST-007, RN-EST-008]
atualizado_em: 2026-09-05
---

# RT-EST-002 — Entrada de lote

## 1. Objetivo

Registrar a caixa que chegou. É onde o **custo** entra no sistema — sem ele não há margem, e sem
validade não há FEFO.

## 2. Movimento é imutável; saldo é projeção

Não existe coluna `saldo` para alguém atualizar. O saldo de um lote é `sum(quantidade)` dos
movimentos dele.

É a mesma disciplina do livro-razão, e o que ela compra é grande: **não há saldo que desande**. Não
existe gravação concorrente que possa perder uma baixa, nem valor errado que precise de um script
para consertar — se a soma dos movimentos está certa, o saldo está certo, por construção.

O preço é uma agregação em toda leitura de lote. É barato: um salão tem dezenas de lotes por
produto, não milhões.

> **Há teste que prova isso.** Ele apaga o movimento direto no banco e confere que o lote passa a
> dizer zero. Se houvesse coluna de saldo, o lote continuaria afirmando que tem dez.

**O sinal é que distingue entrada de saída**, não o tipo. Uma coluna `tipo` sozinha exigiria que
toda soma soubesse quais tipos somam e quais subtraem — e a primeira consulta que esquecesse um
tipo novo daria um número plausível e errado.

## 3. Sem `quantidade_inicial`

O desenho em `04-modelo-de-dados` tem uma coluna `quantidade_inicial` no lote. Ela não existe aqui.

Seria uma **segunda fonte** para o mesmo número: a projeção dos movimentos já responde tanto
"quanto veio" (o movimento de `ENTRADA`) quanto "quanto resta" (a soma). Duas fontes divergem — a
primeira vez que alguém gravar uma sem a outra, o lote passa a afirmar duas coisas. É a mesma razão
de RN-ATD-008.

## 4. O lote e o movimento nascem juntos

O lote é a **caixa**: custo, validade, código impresso. O movimento de entrada é o **lançamento**
que põe as unidades no saldo.

Um sem o outro seria lote fantasma (existe e não tem nada) ou movimento órfão (unidades sem custo
nem validade). Por isso são uma transação só.

## 5. Duas caixas, dois custos

É por isso que custo não é coluna do produto: a mesma prateleira tem unidades que custaram vinte e
unidades que custaram vinte e cinco. O custo médio ponderado (RT-EST-003) é o que junta as duas, e
ele só é calculável porque cada lote guarda o seu.

## 6. A ordem do FEFO vem pronta do banco

`validade nulls last, recebido_em` — vence primeiro sai primeiro, o que não tem validade vai por
último, e empate desempata por quem chegou antes.

A ordem é devolvida pela consulta porque é a mesma que RT-EST-004 vai consumir. Deixar cada
chamador reinventar o critério é como metade das implementações de FEFO erra — e o erro só aparece
quando alguma coisa vence no armário.

## 7. Caixa vencida entra

Recusar impediria justamente o caso que **mais** precisa de registro: a caixa que chegou vencida e
vai voltar para o fornecedor. O sistema registra o que aconteceu; quem reclama é o alerta
(RT-EST-008) e quem devolve é RT-EST-005.

**Produto desativado, esse não recebe.** Receber mercadoria de um produto tirado de circulação é
quase sempre o cadastro errado escolhido na tela — reativar é um clique, e descobrir depois que o
saldo entrou no produto errado custa um inventário.

**Custo zero é válido:** amostra e brinde do fornecedor chegam sem custar nada.

## 8. Testes

- [x] `EntradaDeLoteIT` — 11: a caixa entra e o saldo é projeção · **apagar o movimento zera o
      lote** (a prova de que não há coluna de saldo) · duas caixas, dois custos · **a ordem é a do
      FEFO, e o sem validade vai por último** · custo zero é válido · **caixa vencida entra** ·
      quantidade zero ou negativa · custo negativo · **produto desativado não recebe** · produto
      inexistente · um salão não vê o lote do outro
- [x] `ProdutosWebIT` — 9 (3 novos): a caixa entra e o lote nasce com saldo · **os lotes vêm na
      ordem do FEFO** · quantidade zero para na borda

## 9. Pendências

- [ ] **Não há como corrigir uma entrada.** Movimento é imutável e o ajuste é RT-EST-005 — até lá,
      custo ou quantidade digitados errado ficam. É a pendência mais provável de doer no uso real
- [ ] **Não há saldo por produto.** Só por lote: "quantos shampoos eu tenho?" ainda não tem
      resposta, e o custo médio ponderado é RT-EST-003
- [ ] **Nada sai.** `VENDA`, `CONSUMO_INTERNO`, `PERDA`, `AJUSTE` e `DEVOLUCAO` estão no `check` e
      nenhuma rotina os escreve. O estoque só cresce
- [ ] **Não há fornecedor nem nota.** `origem_tipo`/`origem_id` do movimento de entrada apontam
      para o próprio lote; de onde a caixa veio não é registrado
- [ ] **Sem alerta de vencimento.** A validade é guardada e ninguém a olha (RT-EST-008)
- [ ] **Não há tela.** As rotas existem e o front não as usa
