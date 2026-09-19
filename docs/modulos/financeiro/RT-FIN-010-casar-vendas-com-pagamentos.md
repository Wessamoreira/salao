---
id: RT-FIN-010
titulo: "Matching: valor + janela de tempo + NSU/autorização"
modulo: financeiro
fase: 5
perfil: completo
status: implementado
depende_de: [RT-FIN-009, RT-FIN-001]
permissoes: [financeiro:reconcile]
eventos: []
regras: [RN-FIN-029, RN-FIN-030, RN-FIN-031, RN-FIN-032]
atualizado_em: 2026-09-19
---

# RT-FIN-010 — Casar as vendas da Stone com os pagamentos do salão

## 1. Objetivo

Responder, para o mês, as quatro perguntas da conciliação:

- **O que bate** — pagamento no cartão registrado no salão com a venda correspondente na maquininha;
- **O que bate com diferença** — a mesma venda, com a forma trocada, ou cancelada na Stone depois;
- **O que só a maquininha tem** — venda no cartão sem pagamento no sistema: dinheiro que entrou sem
  comanda, ou comanda esquecida;
- **O que só o sistema tem** — pagamento no cartão sem venda na maquininha: registrado errado, ou
  passado em outra máquina.

Resolver cada divergência à mão é RT-FIN-011; taxa e previsão de recebimento, RT-FIN-012.

## 2. As decisões — do dono, em 19/09/2026

| Pergunta | Decisão |
|---|---|
| Quanto tempo pode separar o registro no sistema da venda na maquininha? | **Até 2 horas** |
| Duas vendas de mesmo valor na janela | **Casa pela ordem de horário** — trocar dois pares de mesmo valor e forma não muda dinheiro |
| Mesmo valor e hora, forma diferente (débito × crédito) | **Casa, marcado divergente** — a taxa de cada uma é diferente |

## 3. O que o salão tem para casar

A recepção registra **forma e valor** ao fechar a comanda; NSU, autorização, bandeira e parcelas quase
nunca são digitados (a tela não pede). Então o casamento se apoia no que existe — valor e hora — e usa os
códigos quando alguém os informou.

## 4. O casamento — três passadas, da prova mais forte para a mais fraca

1. **NSU igual.** Um pagamento e uma venda com o mesmo NSU — e só um de cada lado. NSU repetido não
   casa por NSU: cai para as passadas seguintes.
2. **Autorização igual, dentro da janela.** O código de autorização se repete ao longo do tempo; sozinho
   não prova nada.
3. **Valor exato, dentro da janela.** Todos os pares possíveis, do mais próximo no tempo para o mais
   distante; na mesma distância, a mesma forma primeiro. Cada lado casa uma vez.

**A distância vem antes da forma** — e o doc dizia o contrário até o código ser escrito. O pagamento é
registrado logo depois de passar o cartão: o tempo é a prova física. Preferir a mesma forma a qualquer
distância casaria o crédito das 10h com a venda de crédito das 11h, e deixaria sem par o débito das 10h01
que a recepção registrou como crédito por engano — que é o caso comum, e o que a divergência de forma
existe para mostrar. `CasadorTest.distancia_antes_da_forma` prova.

Venda sem hora no arquivo casa com pagamento do **mesmo dia do salão**, em vez de por janela.

**Venda cancelada na Stone não casa** — não é dinheiro. É o caso comum de passar errado, cancelar e
passar de novo: o pagamento casa com a venda refeita.

## 5. Divergência — só do que foi informado

Um par casado é **divergente** quando:

- **forma** — o sistema diz débito e a Stone, crédito, ou o contrário;
- **valor** — casaram pelo NSU ou pela autorização, e o valor difere;
- **bandeira** e **parcelas** — só quando a recepção **informou** o dado e ele difere. Comparar o "1
  parcela" que o sistema assume com o "3x" da Stone marcaria como divergente toda venda parcelada, e o
  aviso deixaria de ser lido;
- **cancelada na Stone** — o par já existia, e uma importação posterior trouxe a venda cancelada: o
  sistema tem o pagamento, e o dinheiro não vem.

O pagamento acompanha: `CONCILIADO` ou `DIVERGENTE` (o campo existe desde RT-FIN-001).

## 6. Quando roda

- **Ao fim de cada importação**, na mesma transação: o resultado já diz quantas vendas casaram.
- **No botão "Conciliar agora"**: pagamento registrado depois da importação daquele período casa aqui.

Rodar de novo é inofensivo: cada pagamento e cada venda têm no máximo um par (`unique` dos dois lados),
e duas rodadas simultâneas disputam a mesma linha no banco.

## 7. "Só no sistema" só onde há extrato

Um pagamento no cartão num mês ainda não importado não é divergência — é falta de extrato. A importação
passa a guardar o **período que o arquivo cobre** (primeira e última venda lida, no dia do salão), e "só no
sistema" considera apenas os dias cobertos por alguma importação. Os outros aparecem como "sem extrato
importado", à parte.

## 8. Pagamento apagado

O estorno da comanda apaga o pagamento (RT-FIN-001). O par vai junto — chave estrangeira com `cascade` —, e
a venda volta a "só na maquininha", que é a verdade depois do estorno.

## 9. As regras

| Regra | O quê |
|---|---|
| RN-FIN-029 | Casa pela prova mais forte, dentro de duas horas, o mais próximo primeiro |
| RN-FIN-030 | Divergência só do que foi informado; forma trocada casa divergente; cancelada não casa |
| RN-FIN-031 | "Só no sistema" só nos dias que alguma importação cobre |
| RN-FIN-032 | O par não se desfaz pela aplicação; o estorno do pagamento o desfaz; rodar de novo é inofensivo |

## 10. Contrato de API

Permissão `financeiro:reconcile` nas três rotas; `ImportacoesDeExtratoWebIT.conferencia` prende o contrato pelo HTTP.

**Conciliar agora**

```http
POST /api/v1/conciliacao/casamentos
Authorization: Bearer <token-com-mfa>
```

```json
{ "casadas": 3, "divergentes": 1, "canceladasDepois": 0 }
```

**Resumo do período** — dias do salão; sem período, o mês corrente até hoje; no máximo 92 dias.

```http
GET /api/v1/conciliacao/resumo?de=2026-09-01&ate=2026-09-30
```

```json
{ "de": "2026-09-01", "ate": "2026-09-30",
  "conciliadas":    { "quantidade": 410, "valor": 38420.50 },
  "divergentes":    { "quantidade": 3,   "valor": 340.00 },
  "soNaMaquininha": { "quantidade": 2,   "valor": 215.00 },
  "soNoSistema":    { "quantidade": 1,   "valor": 90.00 },
  "semExtrato":     { "quantidade": 0,   "valor": 0 } }
```

**A lista de um recorte** — `DIVERGENTES` (padrão), `SO_NA_MAQUININHA`, `SO_NO_SISTEMA`, `CONCILIADAS`;
cursor, das mais recentes para trás.

```http
GET /api/v1/conciliacao/itens?recorte=DIVERGENTES&de=2026-09-01&ate=2026-09-30&cursor=…&limite=50
```

```json
{ "itens": [ { "id": "…", "instante": "2026-09-18T13:05:00Z", "vendaId": "…", "idNaAdquirente": "ST8FA1",
               "capturadaEm": "2026-09-18T13:05:00Z", "horaInformada": true, "valorVenda": 80.00,
               "produto": "CREDITO", "bandeira": "VISA", "parcelas": 1, "pagamentoId": "…",
               "registradoEm": "2026-09-18T13:02:00Z", "valorPagamento": 80.00, "forma": "DEBITO",
               "criterio": "VALOR_E_HORA", "divergencias": [ "FORMA" ] } ],
  "proximoCursor": null, "temMais": false }
```

**Erros**

| Status | Código | Quando |
|---|---|---|
| 403 | `ER-INF-ACESSO_NEGADO` | sem `financeiro:reconcile`, ou dono e gerente ainda sem MFA |
| 400 | `ER-INF-DADOS_INVALIDOS` | `recorte` desconhecido, data que não é data |
| 422 | `ER-FIN-FILTRO_INVALIDO` | início depois do fim, mais de 92 dias, cursor que não veio da tela |

## 11. Dados — `V59`

- `importacao_de_extrato` ganhou `periodo_de` e `periodo_ate`: a primeira e a última venda lida, no dia
  do salão. Nulos nas importações anteriores.
- `conciliacao_de_venda` — o par: pagamento e venda, o critério, as divergências e quando casou.
  `unique` nos dois lados; `on delete cascade` do pagamento; a aplicação não tem `update` nem `delete`,
  com exceção de `update (divergencias)`, que é por onde a venda cancelada depois entra.
- `pagamento.status_conciliacao` passa a andar: `PENDENTE` → `CONCILIADO` ou `DIVERGENTE`.

## 12. Observabilidade

- `Conciliação: N casadas (N divergentes); N canceladas na adquirente depois de casadas` no log, a cada
  rodada — da importação e do botão.
- A conferência é a observação do dono: divergentes e sobras do mês, com valor.

## 13. Testes

**`CasadorTest`** (14) — o algoritmo, sem banco: janela; sem hora casa no mesmo dia; empate pela ordem;
distância antes da forma; na mesma distância, a mesma forma; NSU único casa fora da janela e diz o valor;
NSU repetido cai para valor e hora; autorização só na janela; bandeira e parcelas só quando informadas;
produto desconhecido não diverge; cada lado uma vez; a mesma entrada em qualquer ordem dá os mesmos pares;
normalização do código.

**`ConciliacaoIT`** (16), com Postgres de verdade — vendas pela importação, pagamentos direto na tabela:
`casa_na_importacao`, `janela_de_duas_horas`, `empate`, `forma_trocada`, `nsu`, `cancelada_depois`,
`cancelada_nao_casa`, `conciliar_agora`, `rodar_de_novo`, `pagamento_apagado`, `sem_extrato`,
`isolamento`, `sem_permissao`, `imutavel`, `cursor`, `periodo_invalido`.

**`ImportacoesDeExtratoWebIT.conferencia`** — resumo, lista e "Conciliar agora" pelo HTTP; a recepção
recebe 403.

**Front** — `Conciliacao.test.tsx` (+5): as quatro contas com os divergentes abertos, o recorte pedido ao
servidor, o mês como `de`/`ate` inteiros, "Conciliar agora", o aviso de dias sem extrato; e o resultado da
importação dizendo quantas casaram.

## 14. O que os testes pegaram

- **A tela falava com o banco.** O controller usava a lista de recortes e o item definidos no JDBC.
  `ArquiteturaTest.controller_nao_chama_repository` reprovou; os tipos foram para o caso de uso.
- **`andexists`, de novo.** O trecho "dia coberto por extrato" era somado ao fim de um bloco de texto que
  terminava em "and " — e o bloco apaga o espaço no fim da linha. É o mesmo defeito de RT-CNV-007. Agora
  entra por um marcador `{coberto}` no meio do SQL, e o comentário diz por quê.

E a prévia na tela pegou a coluna "O que difere" cortada na borda — a linha do valor divergente alargava a
tabela. As células dos divergentes quebram; data, Stone ID e etiqueta, não.

## 15. Mutações verificadas

Uma rodada, 17 mutações, cada uma sozinha. **17 mortas**, todas pelo teste esperado.

| # | Mutação | Morta por |
|---|---|---|
| G01 | janela de três horas | `ConciliacaoIT.janela_de_duas_horas` |
| G02 | empate pelo mais distante | `CasadorTest.empate_pela_ordem` e outros dois |
| G03 | forma antes da distância | `CasadorTest.distancia_antes_da_forma` |
| G04 | NSU repetido casa por NSU | `CasadorTest.nsu_repetido` |
| G05 | autorização casa fora da janela | `CasadorTest.autorizacao` |
| G06 | parcelas comparadas sem terem sido informadas | `CasadorTest.so_o_informado` |
| G07 | forma nunca diverge | `CasadorTest.distancia_antes_da_forma` |
| G08 | venda sem hora tratada como meia-noite na janela | `CasadorTest.sem_hora_mesmo_dia` |
| G09 | venda cancelada casa | `ConciliacaoIT.cancelada_nao_casa` |
| G10 | cancelada depois de casada não marca o par | `ConciliacaoIT.cancelada_depois` |
| G11 | o pagamento não muda de estado | `ConciliacaoIT.casa_na_importacao` e outros quatro |
| G12 | "só no sistema" sem olhar a cobertura | `ConciliacaoIT.sem_extrato` |
| G13 | a importação não concilia | `ConciliacaoIT.casa_na_importacao` e outros seis |
| G14 | "Conciliar agora" sem `@PreAuthorize` | `ConciliacaoIT.sem_permissao` |
| G15 | a aplicação pode desfazer o par (sem `revoke`) | `ConciliacaoIT.imutavel` |
| G16 | o par sobrevive ao estorno (sem `cascade`) | `ConciliacaoIT.pagamento_apagado` |
| G17 | período sem teto | `ConciliacaoIT.periodo_invalido` |

## 16. Pendências

- [ ] **Resolver cada divergência, com motivo e trilha** — RT-FIN-011. Hoje a tela mostra; não desfaz nem
      confirma par.
- [ ] **A recepção não informa NSU, bandeira nem parcelas** (a tela de pagamento pede só forma e valor).
      Com eles, o casamento sobe de "valor e hora" para "NSU" — a prova mais forte.
- [ ] **O mês padrão é o corrente.** Quem importa o mês passado escolhe o mês na conferência.
- [ ] **A janela é constante** (`Conciliador.JANELA`), decisão do dono. Se variar por salão, vira
      configuração.
- [ ] **Casamento 1 para 1.** Uma venda dividida em duas no sistema (ou o contrário) fica como sobra dos
      dois lados — é para a resolução manual (RT-FIN-011).
