---
id: RT-EST-004
titulo: Baixa FEFO — venda e consumo interno
modulo: estoque
fase: 3
perfil: completo
status: implementado
depende_de: [RT-EST-003]
permissoes: []
eventos: []
regras: [RN-EST-011, RN-EST-012, RN-EST-013]
atualizado_em: 2026-09-05
---

# RT-EST-004 — Baixa FEFO

## 1. Objetivo

Tirar do estoque o que saiu, do lote certo, e dizer quanto aquilo custou. É a última perna que
faltava para a comanda poder fechar.

## 2. FEFO, e não FIFO

Num salão o que estraga não é o que chegou primeiro — é o que **vence** primeiro. Uma caixa
comprada ontem com validade curta precisa sair antes de uma comprada no mês passado com validade
longa. FIFO deixaria a primeira vencer no armário, que é justamente o desperdício que o sistema
existe para evitar.

O que não tem validade sai por último: não corre risco de perder.

## 3. Uma baixa quase nunca é de um lote só

Vender três quando o lote mais velho tem duas tira duas de lá e uma do seguinte. Os dois custaram
diferente, e o custo **desta retirada** é a média ponderada do que saiu de cada um.

**Não é o custo médio do produto** (RT-EST-003). São números diferentes, de propósito:

| | O que é | Para que serve |
|---|---|---|
| Custo médio do **produto** | média do que está na prateleira | valor do inventário |
| Custo médio da **retirada** | média do que saiu naquela venda | margem daquela venda |

Há teste que prova a diferença nos dois sentidos: baixando as duas unidades de R$ 20,00, a retirada
custa 20 e o produto passa a valer 30.

É a média da retirada que vira `item_comanda.custo_medio_unitario` em RT-ATD-006.

## 4. `for update` — e o que quase deu errado

Duas comandas fechando ao mesmo tempo disputam as últimas unidades do mesmo lote. Sem lock, as duas
leem o mesmo saldo, as duas decidem que cabe, e o lote fica negativo — **o saldo ser projeção não
protege disso**: projeção resolve *saldo errado*, não *corrida*.

A primeira versão fazia lock e leitura num comando só:

```sql
select l.id, ..., (select sum(m.quantidade) ... ) as saldo
  from lote l where l.produto_id = :p order by ... for update
```

**E não funcionou.** O teste de concorrência mostrou as duas baixas passando, cada uma levando a
mesma última unidade.

A causa é o `READ COMMITTED`. Quando a segunda transação bloqueia no `for update` e finalmente pega
a linha, o PostgreSQL reavalia a *linha travada* na versão nova — mas a subconsulta do saldo, que
está na lista de seleção, já foi resolvida com o snapshot do início do comando. A segunda transação
**acorda com o lock na mão e o saldo de antes**.

A correção é separar em dois comandos: travar primeiro, ler depois. Em `READ COMMITTED` cada
comando pega um snapshot novo, então o segundo `select` já enxerga o commit do vizinho.

> É uma lição do mesmo tipo das que este projeto já colecionou — *"anotação de transação só vale
> onde o Spring consegue interceptar"*, *"exceção que a borda provoca precisa de tradutor"*: a
> ferramenta estava lá, escrita, e não fazia o que parecia fazer. Só o teste que provoca a corrida
> mostra.

E o `order by` é o mesmo para todas as transações. Não é detalhe: quem trava as mesmas linhas em
ordens diferentes faz deadlock.

## 5. Recusa diz quanto **há**

*"Não há saldo suficiente"* não responde a pergunta de quem está no balcão: **vendo o que resta, ou
troco o produto?** A mensagem traz o número — *"há 3 em estoque e a saída é de 5"* — e nada é
baixado quando a saída é recusada.

## 6. Não há rota HTTP, e é o oposto de RT-ARQ-001

Baixa não é ação de usuário: é **consequência** de fechar comanda (RT-ATD-006) ou de executar
serviço com ficha técnica (RT-EST-007).

Uma rota que tirasse unidades do estoque diretamente seria um caminho para o saldo diminuir sem que
nada explicasse por quê — e é exatamente esse caminho que RT-EST-005 fecha, exigindo motivo para
perda e ajuste.

Em RT-ARQ-001 a falta de porta HTTP era pendência, porque lá o caso de uso *era* a ação. Aqui ele é
o efeito de outra, e a porta seria o defeito.

## 7. Testes

- [x] `BaixaFefoIT` — 13: **sai o que vence primeiro, não o que chegou primeiro** · o sem validade
      sai por último · **a baixa atravessa lotes, com média ponderada do que saiu** · **o custo da
      retirada é diferente do custo médio do produto** · o saldo acompanha · lote vazio é pulado ·
      consumo interno pelo mesmo caminho · **a recusa diz quanto há** · nada é baixado quando
      recusa · quantidade zero ou negativa · produto sem estoque · **duas baixas simultâneas não
      levam a mesma última unidade** · um salão não baixa o estoque do outro

## 8. Pendências

- [ ] **Nada consome esta rotina ainda.** Quem vai chamar é RT-ATD-006, a próxima. Até lá, o
      estoque continua só crescendo pelas entradas
- [ ] **Não há como desfazer uma baixa.** Reabrir comanda (RT-ATD-007) vai precisar devolver as
      unidades **ao lote de onde saíram** — a informação está nas parcelas da baixa e não é
      guardada em lugar nenhum além dos próprios movimentos
- [ ] **O lock é por produto, não por lote.** Travar todos os lotes do produto serializa duas
      vendas de produtos iguais mesmo quando elas não disputariam o mesmo lote. É simples e correto;
      fica caro só num volume que este salão não tem
- [ ] **Não há reserva.** Entre lançar o produto na comanda e fechar, outra comanda pode levar a
      última unidade — e quem descobre é a segunda a fechar, com a cliente na frente
- [ ] **Nada impede baixar de lote vencido.** O FEFO tira dele primeiro, que é o certo para
      consumo, e errado para venda: produto vencido não deveria ir para a sacola da cliente
      (RT-EST-008 alerta; ninguém barra)
