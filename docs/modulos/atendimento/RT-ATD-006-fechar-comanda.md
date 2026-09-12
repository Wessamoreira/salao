---
id: RT-ATD-006
titulo: Fechar comanda — transacional (estoque + pagamento + comissão + evento)
modulo: atendimento
fase: 2
perfil: completo
status: implementado
depende_de: [RT-ATD-004, RT-FIN-001, RT-EST-004]
permissoes: [comanda:close]
eventos: [EV-ATD-001]
regras: [RN-ATD-019, RN-ATD-020, RN-ATD-021]
atualizado_em: 2026-09-05
---

# RT-ATD-006 — Fechar comanda

## 1. Objetivo

Encerrar o atendimento. Aqui os itens viram faturamento, o estoque diminui, a comissão para de
mudar e a comanda vira registro.

## 2. Tudo numa transação, e nesta ordem

1. **Conferir que está pago.** Fechar com saldo aberto faria a comanda afirmar que o atendimento
   foi pago quando não foi.
2. **Fechar**, com `where status = 'ABERTA'`. Vem **antes** da baixa de propósito: assim dois
   fechamentos simultâneos não chegam os dois ao FEFO, e o estoque não é baixado duas vezes pela
   mesma venda.
3. **Baixar o estoque** de cada item de produto, gravando no item o custo com que as unidades
   saíram.
4. **Publicar o evento.**

Se qualquer passo falhar, nada acontece — inclusive o fechamento. **Uma comanda fechada com estoque
não baixado seria a pior combinação possível**: o dinheiro entrou, o produto saiu, e o sistema acha
que ele ainda está no armário. Há teste que provoca a falha e confere que a comanda continua
`ABERTA` e o saldo intacto.

## 3. O `custo_medio_unitario` finalmente é preenchido

Ele era nulo desde RT-ATD-003, com a pendência dizendo o porquê: *"quem o preenche é a baixa, que é
quem sabe de qual lote a unidade saiu"*. É esta rotina que fecha o ciclo.

E o número gravado é a **média da retirada** (RN-EST-012), não o custo médio do produto. Preenchê-lo
no lançamento com o custo médio faria a margem daquela venda mudar quando a próxima caixa chegasse
mais cara — que é exatamente o que o snapshot existe para impedir.

## 4. A comissão não é congelada aqui — ela **para de mudar**

Não há passo de snapshot no fechamento, e a ausência é a decisão.

`percentual_comissao` e `regra_origem` já eram snapshot desde o lançamento (RN-ATD-010).
`base_calculo` e `valor_comissao` acompanhavam o desconto enquanto a comanda estava aberta. Fechada,
ela não aceita mais alteração (RN-ATD-006) — e é isso, e só isso, que congela os números.

Um passo de "congelar" copiaria valores que já estão certos para colunas que já os contêm.

Apurar a comissão — virar lançamento e extrato — é RT-FIN-003, que ouve o evento publicado aqui.

## 5. Estoque insuficiente recusa o fechamento

É a escolha difícil da rotina, e ela **trava o caixa**: a cliente está na frente, o produto foi
entregue, e o sistema diz que não tem.

A alternativa — fechar com estoque negativo — resolveria o minuto e estragaria o mês: o inventário
passa a mentir, e ninguém descobre até a contagem.

A recusa tem conserto imediato **na maioria das vezes**, porque a causa quase sempre é uma caixa que
chegou e não foi registrada: a mensagem diz qual produto e quanto há, e registrar a entrada é uma
tela. O caso em que ela incomoda de verdade — o produto sumiu mesmo — precisa de `PERDA`, que é
RT-EST-005, e está nas pendências.

É a mesma escolha que o projeto vem fazendo desde RN-ATD-005: recusar e explicar, em vez de aceitar
e corromper.

## 6. Comanda vazia fecha

Aberta por engano, ela precisa poder sumir do balcão. RN-ATD-001 dá **uma** comanda aberta por
cliente: uma vazia esquecida bloquearia a próxima daquela cliente para sempre, e ficaria na barra de
comandas abertas (R-UX-07) sem nunca sair.

Fechada com total zero, ela é um registro verdadeiro — *abriu, não consumiu nada* — e libera a
cliente.

## 7. `POST /{id}/fechamento`, sem corpo

Não há o que informar: o que decide o fechamento já está na comanda. `POST` num sub-recurso, e não
`PATCH` de status, porque fechar não é editar um campo — é um ato, com efeitos em três módulos.

## 8. Testes

- [x] `FecharComandaIT` — 11: **paga, fecha e baixa o estoque** · **o custo da retirada chega ao
      item** · comanda vazia fecha · **comanda só de serviço não toca no estoque** · **saldo aberto
      não fecha, e a recusa diz quanto falta** · fiado quita · **estoque insuficiente recusa o
      fechamento inteiro** · **recusada a baixa, a comanda continua ABERTA e o saldo intacto** ·
      fechar duas vezes é recusado e não baixa duas vezes · **fechada não recebe mais item,
      pagamento nem desconto** · sai da barra e libera a cliente para abrir outra
- [x] `ComandasWebIT` — 21 (1 novo): **com saldo aberto responde 422; paga, responde a comanda
      FECHADA** e a barra do balcão esvazia

## 9. Pendências

- [ ] **Ninguém consome `ComandaFechada`.** O `_modulo.md` da agenda declara que ele move o
      agendamento para `CONCLUIDO`, e esse listener não existe — e não funcionaria hoje, porque o
      agendamento nunca chega a `EM_ATENDIMENTO` (a tensão registrada em RT-ATD-001)
- [ ] **A comissão não vira lançamento.** RT-FIN-003 é quem apura; até lá, `executante` tem os
      valores e nada os soma num extrato
- [ ] **Não há razão nem faturamento consolidado.** "Quanto entrou hoje" é uma soma de pagamentos
- [ ] **Não se desfaz.** Reabrir e estornar é RT-ATD-007 — e vai precisar devolver as unidades ao
      lote de onde saíram, informação que só existe nos movimentos
- [x] ~~**Fechar não avisa a cliente.**~~ O comprovante existe (RT-FIN-008); falta a tela de fechamento oferecê-lo no mesmo passo
- [ ] **Não há tela.** A de fechamento em um passo é RT-ATD-008 — e é ela que torna tudo isto
      utilizável por quem está no balcão
