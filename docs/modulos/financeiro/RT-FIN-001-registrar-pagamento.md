---
id: RT-FIN-001
titulo: Registrar pagamento (1:N com a comanda, split)
modulo: financeiro
fase: 2
perfil: completo
status: implementado
depende_de: [RT-ATD-001, RT-ATD-002]
permissoes: [comanda:close]
eventos: []
regras: [RN-FIN-001, RN-FIN-002, RN-FIN-003, RN-FIN-004]
atualizado_em: 2026-09-05
---

# RT-FIN-001 — Registrar pagamento

## 1. Objetivo

Receber. É a perna que faltava para a comanda poder fechar.

## 2. Uma comanda, vários pagamentos

*"Metade no cartão e metade no pix"* é o caso comum do balcão, não a exceção. Um campo
`forma_pagamento` na comanda obrigaria a escolher uma, e o salão registraria a maior — perdendo a
taxa da maquininha da outra metade, que é justamente o número que o dono quer ver no fim do mês.

E a conciliação (ADR-0007) casa **pagamento a pagamento**: o extrato do adquirente traz uma linha
por transação, não uma por comanda.

## 3. A direção da dependência, decidida aqui de propósito

Esta é a decisão estrutural da rotina, e ela precisava ser tomada **antes** de RT-ATD-006.

Fechar comanda é transacional em estoque, pagamento e comissão, e quem orquestra isso é o
`atendimento` — ele *tem* de poder perguntar ao financeiro "quanto já foi pago?". Se o financeiro
também dependesse do atendimento, para validar a comanda, o Spring Modulith reprovaria o build por
ciclo. E a descoberta viria lá na frente, com o fechamento meio escrito.

Então:

| Quem | Sabe |
|---|---|
| `financeiro` | o pagamento existe, quanto vale, que forma tem, e quanto soma para uma **origem** |
| `atendimento` | que aquela origem é uma comanda, se ela está aberta, e quanto ela ainda deve |

`comandaId` é um id opaco para o financeiro. É a mesma forma de `arquivo.dono_id` (RT-ARQ-001) e de
`ClienteApi.podeReceber` (RT-NOT-001): **o módulo que possui a pergunta é quem a responde**.

Por isso não há FK de `pagamento` para `comanda`, ao contrário do que `04-modelo-de-dados` desenhou:
o modelo é anterior à regra de fronteira estar aplicada de ponta a ponta, e a FK também não
separaria tenant (RN-CAT-011).

## 4. Troco não é pagamento

Quem entrega cem numa conta de oitenta pagou oitenta. Registrar cem faria o faturamento do dia
crescer sozinho, e o caixa nunca fecharia — o troco não é dado do sistema, é da gaveta.

A soma dos pagamentos nunca passa do valor líquido, e a recusa diz **quanto falta**: *"faltam
R$ 20,00 e o pagamento é de R$ 150,00"*. "Valor inválido" faria a recepção tentar de novo às cegas.

## 5. Cortesia e fiado são pagamentos

Nenhuma das duas traz dinheiro agora — cortesia nunca traz. Mas as duas **quitam** a comanda.

Tratá-las como "não pagamento" deixaria a comanda aberta para sempre, e o salão perderia a única
linha que explica por que aquele atendimento não virou dinheiro. É a mesma escolha de `BLOQUEADA`
em RT-NOT-001: o desfecho que não é o esperado precisa virar registro, não sumiço.

## 6. Cartão nasce pendente; dinheiro, não aplicável

Deixar dinheiro e pix como `PENDENTE` encheria a fila da conciliação com linhas que nunca vão sair
de lá — não existe extrato de adquirente para casar com dinheiro.

`taxa_prevista` e `previsao_recebimento` ficam **nulos**, e nulo não é zero: zero significaria taxa
isenta, e o que é verdade hoje é "ninguém cadastrou o contrato do adquirente" (Fase 5). As colunas
existem desde já porque o fechamento do período (RT-FIN-006) precisa da diferença entre o que foi
cobrado e o que cai na conta — e é essa diferença que faz o caixa do dono não bater com o
faturamento.

## 7. Só comanda aberta recebe

Depois de fechada, o dinheiro já virou comissão apurada e lançamento no razão. Mexer nele é estorno
(RT-ATD-007), com trilha — nunca linha apagada.

Enquanto aberta, remover um pagamento **apaga** mesmo, pela mesma razão do item lançado errado
(RN-ATD-009): valor digitado trocado não é fato do caixa, é digitação. A auditoria guarda forma e
valor do que saiu.

## 8. Testes

- [x] `RegistrarPagamentoIT` — 13: **metade no cartão e metade no pix** · o restante cai a cada
      forma · **cortesia quita sem dinheiro entrar** · **troco não é pagamento** · o segundo
      pagamento também respeita o que falta · valor zero · **só crédito parcela** · comanda que não
      está aberta · **cartão nasce pendente, dinheiro não aplicável** · taxa nula não é zero ·
      remover devolve o restante · pagamento de outra comanda · um salão não vê o do outro
- [x] `ComandasWebIT` — 20 (2 novos): **split, com o restante em cada resposta** · pagamento acima
      do que falta responde 422

## 9. Pendências

- [ ] **A comanda ainda não fecha.** Pagar até zerar não muda o status: quem fecha é RT-ATD-006, que
      ainda espera a baixa de estoque (RT-EST-004)
- [ ] **Não há taxa de adquirente.** `taxa_prevista` e `previsao_recebimento` são nulos, e sem eles
      o dono vê faturamento, não caixa — a diferença é de uns 3% do que passa no crédito
- [ ] **Fiado não tem cobrança.** A forma existe e nada acompanha o que ficou a receber: não há
      lista, aviso nem baixa posterior
- [ ] **Não há razão.** `lancamento` está no modelo e não existe em código; sem ele, "quanto entrou
      em março" é uma soma de pagamentos, não um livro
- [ ] **Nada valida NSU repetido.** Dois pagamentos com o mesmo NSU passam, e a conciliação da Fase
      5 vai encontrar os dois
- [ ] **Não há tela.** As rotas existem; a tela de fechamento em um passo é RT-ATD-008
