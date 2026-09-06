---
id: RT-ATD-003
titulo: Adicionar item de produto
modulo: atendimento
fase: 2
perfil: completo
status: implementado
depende_de: [RT-ATD-001, RT-EST-001]
permissoes: [comanda:open]
eventos: []
regras: [RN-ATD-017, RN-ATD-018]
atualizado_em: 2026-09-05
---

# RT-ATD-003 — Item de produto

## 1. Objetivo

Vender o shampoo. É a linha que faltava para a comanda representar o que a cliente leva embora, e a
rotina que estava travada esperando o cadastro de produto.

## 2. Três recusas diferentes, com correções diferentes

O produto pode não existir, existir e não estar à venda, ou estar à venda sem preço. São três telas
diferentes para quem está no balcão:

| Recusa | O que a pessoa faz |
|---|---|
| `PRODUTO_NAO_ENCONTRADO` | procura outro, ou cadastra |
| `PRODUTO_NAO_E_VENDAVEL` | é tinta, ou saiu de linha — **digitar preço não resolve** |
| `PRODUTO_SEM_PRECO` | falta o dono precificar, e é ele quem pode |

Um "não deu" só serviria para as três, e a recepção ficaria adivinhando qual delas era — com a
cliente esperando.

E **sem preço é recusa, não zero**: é a mesma regra do serviço (RN-ATD-005), do outro lado do
balcão. Zero seria um brinde, e a falha de cadastro viraria faturamento perdido silencioso.

## 3. Produto não tem executante

A pergunta 3 de `13-perguntas-em-aberto` — *produto paga comissão?* — tem como padrão de trabalho
**0%**. Com zero, a linha de executante não teria o que guardar: seria uma comissão de nada para
alguém que ninguém escolheu.

O preço disso é real: **quem vendeu não fica registrado**. No dia em que o salão resolver premiar
venda de produto, essa informação não estará lá para o período anterior. Quando a cascata
(RT-FIN-002) trouxer o nível de produto, é ela que decide se a linha passa a existir.

## 4. O estoque não se mexe aqui

Lançar na comanda não baixa saldo. A baixa acontece no **fechamento** (RT-ATD-006), junto com
pagamento e comissão, numa transação só — porque comanda aberta é intenção, e item lançado por
engano seria estoque baixado por engano.

`custo_medio_unitario` fica nulo pelo mesmo motivo: quem o preenche é a baixa (RT-EST-004), que é
quem sabe de qual lote a unidade saiu. Preenchê-lo aqui com o custo de hoje faria a margem de uma
venda antiga mudar sozinha quando a próxima caixa chegasse mais cara.

## 5. Rota separada, não um campo `tipo`

Serviço exige quem executou; produto não tem executante. Um corpo só teria metade dos campos
ignorados conforme o tipo, e a validação de borda viraria um `if`. Duas rotas dizem no endereço o
que cada uma precisa.

## 6. A limpeza que veio junto

`ER-ATD-COMANDA_NAO_ESTA_ABERTA` estava declarado em **cinco** enums `Erros` — um por caso de uso —
e `ER-ATD-ITEM_NAO_ENCONTRADO` em três. Não é redundância inofensiva: mudar o status HTTP ou o
título numa das cópias faria a mesma falha responder diferente conforme a rota que o usuário tocou,
e nada acusaria.

O módulo passou a ter **um enum só**, `ErrosDoAtendimento`, como `catalogo`, `agenda`, `equipe` e
`estoque` já tinham. E as guardas repetidas — "a comanda está aberta?", "este item é dela?",
"a quantidade vale?" — viraram `ComandaEmEdicao`, que era o outro lado da mesma duplicação.

Duas correções apareceram no caminho: `AplicarDesconto` e `RemoverExecutante` respondiam
*"item não encontrado"* e *"executante não encontrado"* quando quem não existia era a **comanda**.

## 7. Testes

- [x] `AdicionarItemDeProdutoIT` — 11: o produto entra e o total sobe · **produto não tem
      executante** · quantidade multiplica · produto `AMBOS` é vendável · **o preço é snapshot** ·
      **as três recusas, cada uma com o seu código** · quantidade zero · produto de outro salão
      não existe para este
- [x] `ComandasWebIT` — 18 (2 novos): **produto entra por rota própria, sem executante** ·
      quantidade zero para na borda

## 8. Pendências

- [ ] **Quem vendeu o produto não é registrado.** Consequência direta do 0% padrão; vira problema
      no dia em que houver prêmio por venda
- [ ] **Não há baixa de estoque.** É de RT-ATD-006 + RT-EST-004, e até lá vender produto não
      diminui nada: o saldo do sistema vai divergir do armário assim que alguém usar isto de
      verdade
- [ ] **Não se vende sem ter.** Nada confere se há unidade em estoque — depende de RT-EST-003
- [ ] **`custo_medio_unitario` fica nulo**, então não há margem por item ainda
- [ ] **Desconto em produto usa a mesma alçada do serviço.** Pode fazer sentido separar: desconto
      em produto sai da margem, não da comissão de ninguém
- [ ] **Não há tela.** A rota existe e o front não a usa
