# Regras de negócio — módulo `financeiro`

---

### RN-FIN-001 — Uma comanda, vários pagamentos

**Enunciado.** O pagamento é 1:N com a comanda. Não existe "forma de pagamento da comanda".

**Motivo.** Metade no cartão e metade no pix é o caso comum do balcão. Um campo único obrigaria a
escolher uma forma, e o salão registraria a maior — perdendo a taxa da maquininha da outra metade,
que é justamente o número que o dono quer ver no fim do mês.

E a conciliação (ADR-0007) casa **pagamento a pagamento**: o extrato do adquirente traz uma linha
por transação.

**Onde é garantida.** Tabela `pagamento` com `comanda_id` sem unicidade · `PagamentoApi.daOrigem`.
**Rotinas.** RT-FIN-001 · **Teste.** `RegistrarPagamentoIT.split`,
`ComandasWebIT.split_de_pagamento`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-FIN-002 — O financeiro não conhece a comanda; quem sabe quanto falta é quem a possui

**Enunciado.** `comandaId` é um id de origem opaco para o módulo `financeiro`. A regra "o pagamento
não pode passar do que falta" vive no `atendimento`, que conhece o total.

**Motivo.** Fechar comanda é transacional em estoque, pagamento e comissão, e quem orquestra é o
`atendimento` — ele precisa perguntar ao financeiro quanto já foi pago. Se o financeiro dependesse
do atendimento para validar a comanda, o build reprovaria por ciclo de módulos.

É a mesma forma de `arquivo.dono_id` (RT-ARQ-001) e de `ClienteApi.podeReceber` (RT-NOT-001): o
módulo que possui a pergunta é quem a responde.

Consequência de modelagem: **não há FK** de `pagamento` para `comanda`, ao contrário do desenho em
`04-modelo-de-dados` — que é anterior à regra de fronteira estar aplicada de ponta a ponta, e cuja
FK também não separaria tenant (RN-CAT-011).

**Onde é garantida.** `PagamentoApi` sem nenhuma referência a comanda ·
`RegistrarPagamentoUseCase` (no atendimento) faz a conta do restante · ausência de FK em V36.
**Rotinas.** RT-FIN-001 · **Teste.** `ArquiteturaTest.modulos_respeitam_fronteiras` ·
`RegistrarPagamentoIT.nao_passa_do_total`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-FIN-003 — Troco não é pagamento; cortesia e fiado são

**Enunciado.** A soma dos pagamentos nunca passa do valor líquido da comanda
(`ER-ATD-PAGAMENTO_PASSA_DO_TOTAL`). `CORTESIA` e `FIADO` são formas de pagamento e quitam a
comanda.

**Motivo.** Quem entrega cem numa conta de oitenta pagou oitenta — registrar cem faria o
faturamento do dia crescer sozinho e o caixa nunca fecharia. O troco é da gaveta, não do sistema.

Do outro lado, cortesia e fiado não trazem dinheiro (a primeira, nunca), e mesmo assim precisam
quitar: tratá-las como "não pagamento" deixaria a comanda aberta para sempre, e o salão perderia a
única linha que explica por que aquele atendimento não virou dinheiro.

A recusa diz **quanto falta**, e não só "valor inválido": sem o número, a recepção tenta de novo às
cegas com a cliente esperando.

**Onde é garantida.** `RegistrarPagamentoUseCase` compara com o restante ·
`FormaDePagamento.traDinheiro` documenta a diferença sem excluir ninguém da soma.
**Rotinas.** RT-FIN-001 · **Teste.** `RegistrarPagamentoIT.nao_passa_do_total`,
`RegistrarPagamentoIT.segundo_pagamento_respeita_o_restante`,
`RegistrarPagamentoIT.cortesia_quita`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-FIN-004 — Só cartão entra na fila da conciliação, e só crédito parcela

**Enunciado.** Pagamento em `CREDITO` ou `DEBITO` nasce `PENDENTE` de conciliação; qualquer outra
forma nasce `NAO_APLICAVEL`. Parcelas acima de 1 só em `CREDITO`. `taxa_prevista` e
`previsao_recebimento` nascem **nulos**.

**Motivo.** Não existe extrato de adquirente para casar com dinheiro — deixá-lo `PENDENTE` encheria
a fila da conciliação com linhas que nunca sairiam de lá, e a tela da Fase 5 nasceria inútil.

Débito em três vezes não existe; aceitar o número faria a previsão de recebimento dividir um
dinheiro que cai de uma vez só.

E nulo não é zero: zero significaria taxa isenta, quando a verdade é que ninguém cadastrou o
contrato do adquirente ainda.

**Onde é garantida.** `Pagamentos.conciliacaoInicial` · `Pagamentos.parcelasValidas` ·
`FormaDePagamento.ehCartao`.
**Rotinas.** RT-FIN-001 · **Teste.** `RegistrarPagamentoIT.status_de_conciliacao`,
`RegistrarPagamentoIT.so_credito_parcela`, `RegistrarPagamentoIT.taxa_ainda_nao_existe`
**Configurável?** Não · **Origem.** 2026-09-05
