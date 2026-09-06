---
id: RT-ATD-007
titulo: Estornar / reabrir comanda
modulo: atendimento
fase: 2
perfil: completo
status: implementado
depende_de: [RT-ATD-006]
permissoes: [comanda:reopen]
eventos: [EV-ATD-002, EV-ATD-003]
regras: [RN-ATD-022, RN-ATD-023, RN-ATD-024]
atualizado_em: 2026-09-05
---

# RT-ATD-007 — Estornar e reabrir

## 1. Objetivo

Desfazer o que foi fechado — de duas maneiras, porque são dois problemas diferentes.

| | Quando | Estado final |
|---|---|---|
| **Reabrir** | *"lancei o item errado e já fechei"* | volta a `ABERTA`, corrige, fecha de novo |
| **Estornar** | *"o atendimento foi desfeito depois de pago"* | `ESTORNADA`, terminal |

## 2. As unidades voltam ao lote de onde saíram

Não é "somar de volta a quantidade". Elas saíram de lotes específicos, com custos e validades
específicos — devolvê-las a um lote genérico faria o custo médio e o FEFO mentirem a partir dali.

A informação de qual lote deu cada pedaço já existia: são os movimentos da baixa, com
`origem_id = item_comanda.id`. A devolução soma esses movimentos **por lote** e devolve o que ainda
está negativo.

**E é movimento novo, de sinal contrário — nunca apagando o da baixa.** É o que RN-EST-006 promete,
e é aqui que a promessa é cobrada pela primeira vez: o razão de estoque não é reescrito, ele ganha a
linha que corrige. Há teste que confere que as duas linhas coexistem.

**Idempotente por construção.** Olhar o saldo líquido da origem em cada lote significa que o que já
voltou soma zero e não aparece na lista de pendentes. Não há marca de "já devolvido" para manter em
dia — o próprio razão responde. Reabrir, fechar e reabrir de novo não devolve duas vezes.

## 3. Os pagamentos ficam — e isso abriu um caso novo

O dinheiro foi recebido de verdade. Apagá-los faria a comanda reaberta parecer não paga, e a
recepção cobraria de novo.

O preço é um caso que não existia antes: **tirar item de uma comanda reaberta pode deixar o pago
acima do total**. Enquanto aberta, nenhum pagamento passa do que falta (RN-FIN-003) — só a
reabertura cria essa situação.

Fechar assim registraria faturamento maior do que o atendimento, e o troco devido ficaria sem rastro
nenhum. Por isso RN-ATD-019 passou a recusar também esse lado, e a mensagem diz o conserto: remover
ou ajustar um pagamento.

> É um caso que só apareceu porque a rotina foi escrita. Nenhuma das anteriores podia produzi-lo, e
> ele não estava previsto em lugar nenhum do plano.

## 4. Motivo obrigatório nos dois

É o que separa a correção legítima do desfazimento silencioso. Sem ele, a trilha registra quem e
quando, e a pergunta que o dono faz — *por que esta comanda de trezentos reais foi estornada?* —
fica sem resposta.

Mesma exigência de RT-AGD-005 para cancelar agendamento, e pela mesma razão: sem motivo obrigatório,
"cancelado" vira a resposta padrão para tudo.

**O estorno guarda o motivo na própria comanda; a reabertura, só na auditoria.** Não é
inconsistência: `ESTORNADA` é terminal, e a linha vai ficar ali para sempre dizendo que um
atendimento pago foi desfeito — *por quê* faz parte do que ela afirma. Reabrir é transitório: a
comanda volta à vida normal, e o que sobra do episódio é trilha.

## 5. Estornar não é apagar

Os itens ficam. Os pagamentos ficam. O que muda é o estado: aquela comanda deixa de valer como
faturamento.

Apagar as linhas faria sumir a única explicação de por que o caixa do dia tem um buraco — e é
justamente ela que alguém vai procurar.

O **dinheiro**, esse volta por fora do sistema hoje: não há devolução de pagamento nem lançamento de
sinal contrário no razão. É RT-FIN-007.

## 6. Os eventos existem para tornar a compensação possível

`ComandaReaberta` e `ComandaEstornada` não têm consumidor. Publicá-los mesmo assim é o contrário do
que foi decidido em RT-ATD-001 para `ComandaAberta`, e a diferença é concreta:

`ComandaFechada` vai ser consumido por RT-FIN-003 para apurar comissão. Reabrir e fechar de novo
publicaria o fechamento **uma segunda vez** — e sem estes eventos no meio, a apuração contaria em
dobro sem nada avisar. A alternativa era deixar uma armadilha silenciosa esperando a próxima rotina.

## 7. Só quem pode

`comanda:reopen` não está no perfil de recepção — ela abre, lança, desconta e fecha, e não desfaz.
Desfazer um fechamento mexe em faturamento, comissão e estoque de uma vez.

Estornar usa a mesma permissão. Uma própria faria sentido (é irreversível, e reabrir não é), e não
existe no mapa — está nas pendências.

## 8. Testes

- [x] `ReabrirEEstornarIT` — 12: **devolve as unidades ao lote de onde saíram** (dois lotes, custos
      diferentes) · **a devolução é movimento novo, não apaga a baixa** · reaberta volta a aceitar
      alteração e o custo do item some · **os pagamentos ficam** · **tirar item pode deixar pago a
      mais, e aí não fecha** · o conserto é remover o pagamento · **reabrir duas vezes não devolve
      duas vezes** · o estorno guarda o motivo · **estornar não apaga** · estornada é terminal ·
      aberta não reabre nem estorna · **sem motivo, nenhum dos dois acontece**
- [x] `ComandasWebIT` — 23 (2 novos): **a recepção fecha e não reabre nem estorna** (403) ·
      reabertura sem motivo para na borda

## 9. Pendências

- [ ] **O dinheiro não volta.** Estornar devolve estoque e não devolve pagamento: não há devolução
      nem lançamento de sinal contrário. RT-FIN-007
- [ ] **Estornar e reabrir compartilham a permissão.** Estornar é irreversível e reabrir não é;
      merecem alçadas diferentes, e o mapa tem só `comanda:reopen`
- [ ] **Ninguém compensa a comissão.** Os eventos são publicados e nada os ouve — quando RT-FIN-003
      existir, é ela que precisa tratar reabertura e estorno, ou vai contar em dobro
- [ ] **Reabrir não avisa ninguém.** Se a cliente já recebeu comprovante (RT-FIN-008), ele fica
      valendo por um fechamento que deixou de existir
- [ ] **Não há limite de tempo.** Reabrir uma comanda de três meses atrás é permitido, e mexeria em
      um período que o fechamento (RT-FIN-006) já pode ter repassado
- [ ] **Não há tela.** As rotas existem e o front não as usa
