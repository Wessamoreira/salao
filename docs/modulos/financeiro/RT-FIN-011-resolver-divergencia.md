---
id: RT-FIN-011
titulo: "Resolução manual de divergência, com trilha"
modulo: financeiro
fase: 5
perfil: completo
status: implementado
depende_de: [RT-FIN-010, RT-FIN-003]
permissoes: [financeiro:reconcile]
eventos: []
regras: [RN-FIN-033, RN-FIN-034, RN-FIN-035, RN-FIN-036]
atualizado_em: 2026-09-20
---

# RT-FIN-011 — Resolver a divergência à mão, com motivo e trilha

## 1. Objetivo

RT-FIN-010 fez a conferência **mostrar**. Aqui ela passa a **fechar**: cada linha ganha uma saída, a
saída pede motivo escrito, e o que foi decidido fica registrado com quem decidiu e quando.

Uma conferência sem saída vira uma lista que só cresce. No terceiro mês ninguém abre mais.

## 2. As decisões — do dono, em 19/09/2026

| Pergunta | Decisão |
|---|---|
| Par divergente (forma trocada) | **Corrigir o pagamento** para o que a Stone diz |
| Par com valor diferente | **Aceitar e lançar a diferença** no razão |
| Par cuja venda a Stone cancelou depois | **Aceitar com motivo** — o par continua |
| Venda que só a maquininha tem | **Lançar entrada no caixa**, e a tela avisa quando pode ser venda refeita |
| Pagamento que só o sistema tem | **Corrigir a forma** (era dinheiro, Pix ou transferência) |
| Motivo | **Escrito, sempre** — sem ele o botão não confirma |
| Quem resolve | Dono e gerente — `financeiro:reconcile`, a permissão que já vê a conferência |
| Resolução errada | **Reabre, com motivo.** A resolução não se reescreve |

## 3. As três ações

Cada linha da conferência tem a ação que faz sentido para o que ela é.

| Linha | Botão | O que acontece |
|---|---|---|
| Par divergente | **Usar a versão da Stone** (`CORRIGIR`) | forma, bandeira e parcelas do pagamento passam a ser as da Stone; diferença de valor vira `AJUSTE` no razão |
| Par divergente | **Aceitar como está** (`ACEITAR`) | nada muda; a divergência fica registrada como vista |
| Só na maquininha | **Lançar no caixa** (`LANCAR`) | `AJUSTE` positivo do valor da venda, no razão |
| Só no sistema | **Corrigir a forma** (`CORRIGIR`) | a forma do pagamento vira dinheiro, Pix ou transferência |

"Usar a versão da Stone" aplica **tudo o que cabe de uma vez**. Um par com forma trocada *e* valor
diferente precisa das duas correções; oferecer uma de cada vez deixaria metade errada, e a segunda
metade sem botão — o alvo já teria saído da lista.

## 4. Corrigir nunca reescreve dinheiro recebido

Forma, bandeira e parcelas são **classificação**: mudam no pagamento, e o caixa continua o mesmo.
Valor é **dinheiro**, e o valor de um pagamento não se reescreve — a comanda já foi apurada, a
comissão da profissional já foi calculada em cima dele, e o mês pode estar fechado (RT-FIN-006).

Então a diferença de valor entra como ela entraria se o dono a tivesse achado no extrato do banco:
um `AJUSTE` no razão, positivo ou negativo, com o motivo na descrição. O caixa passa a bater com o
banco; a comanda e a comissão ficam como estavam.

**A competência é hoje**, e não o dia da venda. É a mesma razão de `Razao.estornarOrigem` e de
`Competencia` não aceitar data de fora: uma resolução de setembro feita em novembro não pode entrar
num período já fechado e pago.

## 5. "Lançar no caixa" e a venda refeita

O caso comum de cancelamento é passar errado, cancelar e passar de novo. A Stone fica com duas
vendas; o salão, com um pagamento. O pagamento casa com a primeira, ela é cancelada, e o par vira
divergente — que o dono **aceita como está**. A segunda venda fica como "só na maquininha".

Lançar essa segunda no caixa contaria **o mesmo R$ 100 duas vezes**: uma pela comanda, outra pelo
ajuste.

Por isso a tela avisa antes de confirmar, quando há **venda cancelada de mesmo valor no mesmo dia do
salão, já casada com um pagamento**. O aviso diz o que o dono não tem como lembrar: que aquele valor
já está no caixa. Ele decide — é a única pessoa que sabe se as duas vendas são a mesma.

O aviso olha só para vendas canceladas **que chegaram a casar**. Cancelada antes da primeira
importação nunca casou, e o pagamento foi direto para a venda refeita: não há o que duplicar.

## 6. Reabrir

Clique errado e motivo errado acontecem. A resolução **não se corrige** — ela se reabre, com motivo
próprio, e os dois registros ficam na trilha. A linha volta para a conferência.

Reabrir desfaz o que a resolução fez:

- **`LANCAR`** e a diferença de **`CORRIGIR`** — o lançamento é estornado no razão, pela origem
  (`CONCILIACAO`, o id da resolução). Nunca apagado: `Razao.estornarOrigem` escreve a linha que anula.
- **`CORRIGIR`** — forma, bandeira e parcelas do pagamento voltam ao que eram. O `efeito` guardado na
  resolução é o que diz ao que voltar, e é por isso que ele existe.
- **`ACEITAR`** — não há o que desfazer.

## 7. O que some da conferência

As contas de RT-FIN-010 passam a contar só o que **ainda não foi resolvido**. Uma quinta conta,
**Resolvidas à mão**, abre a trilha: ação, motivo, quem, quando e o que mudou.

Resolvida não entra em "conciliadas" — a conta de conciliadas responde "o que bate sozinho", e um par
aceito com forma trocada não bate sozinho. Misturar os dois esconderia exatamente o que a trilha
existe para mostrar.

## 8. As regras

| Regra | O quê |
|---|---|
| RN-FIN-033 | Nada sai da conferência sem resolução, e toda resolução tem motivo escrito, quem e quando |
| RN-FIN-034 | Corrigir troca classificação, nunca valor recebido; diferença de valor vira `AJUSTE`, na competência de hoje |
| RN-FIN-035 | Venda sem par vira `AJUSTE` positivo; a tela avisa quando há cancelada de mesmo valor no dia |
| RN-FIN-036 | Resolução não se reescreve: reabre com motivo, desfazendo o que fez; os dois registros ficam |

## 9. Contrato de API

Permissão `financeiro:reconcile` em todas as rotas.

**Resolver**

```http
POST /api/v1/conciliacao/resolucoes
Authorization: Bearer <token-com-mfa>
```

```json
{ "alvo": "PAR", "id": "…", "acao": "CORRIGIR", "motivo": "recepção registrou débito; foi crédito" }
```

`alvo`: `PAR` (o id do par), `VENDA` (o id da venda na conferência) ou `PAGAMENTO`.
`acao`: `CORRIGIR`, `ACEITAR` ou `LANCAR`. `forma` só com `alvo=PAGAMENTO`: `DINHEIRO`, `PIX` ou
`TRANSFERENCIA`.

```json
{ "id": "…", "acao": "CORRIGIR", "formaDe": "DEBITO", "formaPara": "CREDITO", "lancado": 10.00,
  "lancamentoId": "…" }
```

**A trilha**

```http
GET /api/v1/conciliacao/resolucoes?de=2026-09-01&ate=2026-09-30&cursor=…&limite=50
```

```json
{ "itens": [ { "id": "…", "resolvidaEm": "2026-09-19T14:02:00Z", "quemResolveu": "Wesley",
               "acao": "CORRIGIR", "motivo": "recepção registrou débito; foi crédito",
               "alvo": "PAR", "instante": "2026-09-18T13:05:00Z", "valor": 80.00,
               "idNaAdquirente": "ST8FA1", "divergencias": ["FORMA"],
               "formaDe": "DEBITO", "formaPara": "CREDITO", "lancado": null } ],
  "proximoCursor": null, "temMais": false }
```

**Reabrir**

```http
POST /api/v1/conciliacao/resolucoes/{id}/reabertura
```

```json
{ "motivo": "cliente confirmou que pagou no débito" }
```

**Erros**

| Status | Código | Quando |
|---|---|---|
| 403 | `ER-INF-ACESSO_NEGADO` | sem `financeiro:reconcile`, ou dono e gerente ainda sem MFA |
| 422 | `ER-FIN-DADOS_INVALIDOS` | motivo vazio ou maior que 500; forma que não é dinheiro, Pix ou transferência |
| 422 | `ER-FIN-RESOLUCAO_NAO_APLICAVEL` | ação que não cabe no alvo: corrigir um par sem nada a corrigir, lançar uma venda que já tem par |
| 404 | `ER-FIN-RESOLUCAO_NAO_ENCONTRADA` | alvo ou resolução que não existe, ou é de outro salão |
| 409 | `ER-FIN-JA_RESOLVIDA` | o alvo já tem resolução aberta — dois cliques, duas abas |

## 10. Dados — `V60`

`resolucao_de_conciliacao` — um alvo (par, venda ou pagamento), a ação, o motivo, o efeito e quem.
Índice único parcial por alvo **enquanto não reaberta**: um alvo tem no máximo uma resolução valendo,
e pode ter várias ao longo do tempo. `revoke update, delete`, com `grant update` só nas três colunas
da reabertura — a resolução não se reescreve (RN-FIN-036) e o banco é quem garante.

## 11. Observabilidade

- `log.info` por resolução: ação, alvo, valor e se lançou.
- `AuditoriaApi` em toda resolução e reabertura — é decisão sobre dinheiro conferido.

## 12. Testes

**`CorrecaoTest`** (8) — o que "usar a versão da Stone" muda, sem banco: forma; valor a maior e a menor;
bandeira e parcelas; cancelada sozinha não corrige; corrige o que dá e o resto fica; produto desconhecido
não vira forma inventada; forma e valor de uma vez.

**`ResolucaoIT`** (21), com Postgres de verdade — as vendas pela importação, como na tela:
`corrigir_forma`, `corrigir_valor_lanca_a_diferenca`, `aceitar_como_esta`,
`corrigir_par_sem_o_que_corrigir`, `lancar_venda_no_caixa`, `aviso_de_venda_refeita`,
`sem_aviso_quando_nao_ha_cancelada`, `corrigir_forma_do_pagamento`, `forma_de_cartao_recusada`,
`motivo_obrigatorio`, `ja_resolvida`, `acao_que_nao_cabe`, `sem_permissao`, `isolamento`, `imutavel`,
`reabrir_desfaz_a_correcao`, `reabrir_estorna_o_lancamento`, `resolver_de_novo_depois_de_reabrir`,
`reabrir_duas_vezes`, `cancelada_depois_reabre_a_resolucao`, `trilha_conta_a_historia`.

**`ImportacoesDeExtratoWebIT.resolucao`** — o contrato pelo HTTP: motivo vazio dá 400, resolver dá 200 com
o valor lançado, repetir dá 409, a trilha traz o motivo, e a reabertura devolve a linha à conferência.

**Front** — `Conciliacao.test.tsx` (+6): sem motivo o botão não confirma; par só com cancelamento não
oferece corrigir; o aviso de venda refeita; a forma verdadeira indo no corpo; a recusa do servidor
aparecendo sem fechar o painel; a trilha com quem decidiu, o efeito e o reabrir.

## 13. O que os testes e a prévia pegaram

- **O razão não conhecia a origem.** `lancamento.origem_tipo` tem `check`, e `CONCILIACAO` não estava
  nele: 13 erros de integração no primeiro `LANCAR`. Entrou em `V60` com origem própria e não como
  `MANUAL`, pela mesma razão do vale (`V41`): `pendentesDeEstorno('CONCILIACAO', id)` só responde "esta
  resolução ainda vale?" se a origem for dela.
- **Coluna que eu mesmo removi.** Uma primeira versão da migration marcava no par quando as divergências
  mudaram; a solução melhor foi a importação **reabrir** a resolução que ficou velha, e a coluna saiu — mas
  a consulta ainda a lia. Erro de SQL em 6 testes.
- **Sem botão em "só na maquininha".** Só na prévia: a fixture zerava o `vendaId` desse recorte, que o
  servidor sempre manda. A tela estava certa; a prévia é que mentia.
- **O motivo não quebrava linha, e cortava "Reabrir".** `.cnc-motivo-lido` perdia em especificidade para o
  `white-space: nowrap` de `.cnc-tabela td`.
- **No celular o painel passava da borda.** A tabela rola na horizontal e o painel ia junto, com a
  explicação e o botão cortados. Agora ele é `sticky` à esquerda com `width: 100cqi` — a largura
  **visível** da rolagem, e não a da tabela.

## 14. Mutações verificadas

Duas rodadas, 21 mutações, cada uma sozinha. **21 mortas** — duas só depois de a rodada apontar o que
faltava (abaixo).

| # | Mutação | Morta por |
|---|---|---|
| H01 | a forma não é corrigida | `CorrecaoTest.forma` e `ResolucaoIT.corrigir_forma` |
| H02 | diferença invertida (pagamento − venda) | `CorrecaoTest.valor_a_maior`, `.valor_a_menor` |
| H03 | produto desconhecido vira crédito | `CorrecaoTest.produto_desconhecido_nao_inventa_forma` |
| H04 | o cancelamento não sobra: o par vira conciliado | `ResolucaoIT.corrigir_o_que_da_deixa_o_resto` |
| H05 | motivo vazio aceito | `ResolucaoIT.motivo_obrigatorio` |
| H06 | motivo só de espaços aceito (sem `strip`) | `ResolucaoIT.motivo_obrigatorio` |
| H07 | venda que já casou pode ser lançada de novo | `ResolucaoIT.lancar_venda_que_ja_casou` |
| H08 | venda cancelada na adquirente pode ser lançada | `ResolucaoIT.lancar_venda_cancelada` |
| H09 | forma de cartão aceita como "forma verdadeira" | `ResolucaoIT.forma_de_cartao_recusada` |
| H10 | par sem o que corrigir não é recusado | `ResolucaoIT.corrigir_par_sem_o_que_corrigir` |
| H11 | reabrir não estorna o lançamento | `ResolucaoIT.reabrir_estorna_o_lancamento` |
| H12 | reabrir não devolve a classificação do pagamento | `ResolucaoIT.reabrir_desfaz_a_correcao` |
| H13 | reabrir duas vezes não é conflito (as **duas** guardas) | `ResolucaoIT.reabrir_duas_vezes` |
| H14 | corrigir também reescreve o valor do pagamento | `ResolucaoIT.corrigir_valor_lanca_a_diferenca` |
| H15 | a importação não reabre a resolução que ficou velha | `ResolucaoIT.cancelada_depois_reabre_a_resolucao` |
| H16 | o aviso de venda refeita ignora o valor | `ResolucaoIT.sem_aviso_quando_o_valor_difere` |
| H17 | o aviso conta cancelada que nunca casou | `ResolucaoIT.sem_aviso_quando_a_cancelada_nao_casou` |
| H18 | o par resolvido continua na conferência | `ResolucaoIT.corrigir_forma` |
| H19 | resolver sem `@PreAuthorize` | `ResolucaoIT.sem_permissao` |
| H20 | a aplicação pode reescrever a resolução (sem `revoke`) | `ResolucaoIT.imutavel` |
| H21 | gravar em cima de outra não vira 409 | `ResolucaoIT.ja_resolvida` |

**H01 e H02 foram refeitas.** No `-Dtest` do Maven, `+` só separa métodos da **mesma** classe; entre
classes é vírgula. `A#m+B#n` roda um teste só, em silêncio — as duas morriam pelo primeiro teste
nomeado, e a tabela citava dois. Refeitas com o filtro certo, morrem pelos dois.

**As duas que sobreviveram na primeira rodada, e o que cada uma disse:**

- **H16 era buraco de teste de verdade.** `sem_aviso_quando_nao_ha_cancelada` não tinha venda cancelada
  nenhuma, então o aviso ficava falso com ou sem a comparação de valor. Entrou
  `sem_aviso_quando_o_valor_difere`: uma cancelada **que casou**, do mesmo dia, de outro valor — o caso em
  que ignorar o valor daria alarme falso.
- **H13 era redundância, não buraco.** Reabrir tem duas guardas: a leitura (`reabertaEm != null`), que
  evita a escrita à toa, e o retorno do `update`, que fecha a corrida de duas reaberturas ao mesmo tempo.
  Derrubar **uma** não muda nada em sequência — a outra pega. A mutação passou a derrubar as duas, e aí
  morre. A guarda da corrida continua sem teste próprio, e é de propósito: testar corrida de verdade aqui
  sai flaky.

## 15. Pendências

- [ ] **Não existe par manual.** Uma venda dividida em duas no sistema (ou o contrário) continua sobra
      dos dois lados: dá para lançar uma e corrigir a outra, mas não para dizer "estas duas são a mesma".
      Herdada de RT-FIN-010.
- [ ] **Lançar no caixa não cria comanda.** O ajuste acerta o dinheiro; não há cliente, comissão nem
      baixa de estoque. Uma venda de produto recorrente sem comanda vira problema de estoque, não de
      caixa — e esta tela não resolve isso.
- [ ] **A reabertura automática é silenciosa.** O par volta à conferência quando a Stone cancela a venda
      depois, e só quem abrir a tela vê. Um aviso para a equipe (RT-CNV-006) resolveria.
- [ ] **Venda lançada no caixa e cancelada na Stone depois** some da conferência — o recorte só lista
      aprovadas — e o ajuste fica. É o espelho do caso que RN-FIN-036 resolve para os pares, e aqui ainda
      não tem tratamento.
- [ ] **O aviso de venda refeita é do mesmo dia e mesmo valor.** Cancelamento na virada da meia-noite, ou
      venda refeita com valor diferente (gorjeta, ajuste), passa sem aviso.
- [ ] **A trilha não filtra.** Lista o período inteiro, da mais recente para trás. Por ação, por pessoa ou
      por alvo seria útil quando houver centenas.
