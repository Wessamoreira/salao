---
id: RT-FIN-007
titulo: Estorno de lançamento
modulo: financeiro
fase: 2
perfil: completo
status: implementado
depende_de: [RT-FIN-003, RT-FIN-006]
permissoes: [financeiro:close]
eventos: []
regras: [RN-FIN-021, RN-FIN-022, RN-FIN-023]
atualizado_em: 2026-09-12
---

# RT-FIN-007 — Estorno de lançamento

## 1. Objetivo

Consertar o razão sem apagar nada, e desfazer um fechamento sem perder o documento de que o
pagamento existiu.

## 2. Contexto de negócio

Duas coisas eram impossíveis antes desta rotina.

**Anular uma linha só.** O estorno existia por *origem*: todas as linhas de uma comanda, todas de
um vale. Uma comissão lançada para a profissional errada obrigava a reabrir a comanda inteira —
desfazendo receita, baixa de estoque e a comissão de quem estava certo — para consertar uma linha.

**Desfazer um fechamento.** RT-FIN-006 deixou isso como a pendência mais séria: o `EXCLUDE` que
impede pagar duas vezes mora no documento, e enquanto a linha existe o período continua ocupado.

## 3. Atores e permissões

`financeiro:close`, nos dois estornos. Não é a permissão de leitura: mexer no razão consolidado é
o mesmo grau de poder que fechar o período, e hoje cai nos mesmos perfis (ADMIN e GERENTE). Uma
permissão própria entra quando algum salão pedir a separação.

## 4. Pré-condições

- O lançamento ou o fechamento existe **neste** salão (a RLS resolve; o caso de uso devolve 404).
- Motivo preenchido.
- Para o fechamento: nada. Estornar um já estornado devolve o mesmo estado.

## 5. Fluxo principal

**Lançamento.** Lê a linha · recusa se for `ESTORNO` · devolve zero se já estiver anulada · escreve
a linha `ESTORNO` de sinal contrário **na competência da original** · audita.

**Fechamento.** Trava o estabelecimento · lê o documento · devolve o mesmo se já estornado · anula
as linhas de `REPASSE` pendentes, na competência de cada uma · preenche `estornado_em` · audita.

## 6. Fluxos alternativos

| Situação | Resposta |
|---|---|
| motivo vazio | `ER-FIN-DADOS_INVALIDOS` |
| linha é um `ESTORNO` | `ER-FIN-ESTORNO_DE_ESTORNO` (422) |
| linha já anulada | `200` com `anuladas: 0` |
| fechamento já estornado | `200` com o mesmo documento e a data do **primeiro** estorno |
| id de outro salão | 404 — a RLS não o enxerga |

## 7. Regras de negócio aplicadas

RN-FIN-021 (competência da anulação) · RN-FIN-022 (documento fica, período sai) ·
RN-FIN-023 (estorno não se estorna) · e as que continuam valendo: RN-FIN-008 (razão append-only),
RN-FIN-018 (lock por estabelecimento), RN-FIN-020 (contracheque imutável).

## 8. Contrato de API

| Rota | Corpo | Resposta |
|---|---|---|
| `POST /api/v1/lancamentos/{id}/estorno` | `{ "motivo": "…" }` | `{ "anuladas": 1 }` |
| `POST /api/v1/fechamentos/{id}/estorno` | `{ "motivo": "…" }` | o contracheque, com `estornadoEm` preenchido |

`POST` num sub-recurso, e não `DELETE`, nos dois casos: nada é apagado. A role não tem `delete`
nessas tabelas, e o verbo precisa prometer o que acontece.

## 9. Dados

`fechamento` ganha `estornado_em timestamptz` (V43). O `EXCLUDE` de sobreposição passa a ser
**parcial** (`where estornado_em is null`), e há índice parcial `idx_fechamento_vigente`.

Nenhuma tabela nova, nenhum dado migrado: a coluna nasce nula e a constraint parcial cobre as
linhas existentes exatamente como antes.

## 10. A decisão que sustenta a rotina: `GRANT` por coluna

O `EXCLUDE` mora no documento, e o documento é imutável por permissão. As três saídas óbvias são
piores:

| Saída | Por que não |
|---|---|
| apagar a linha | perde o registro de que o pagamento existiu — o que explica o dinheiro que saiu |
| tirar o `EXCLUDE` e checar em Java | troca garantia do banco por trava que não resiste a duas instâncias |
| `grant update on fechamento` | quebra RN-FIN-020 e permite "corrigir" um `valor_repassado` já pago |

O PostgreSQL concede `UPDATE` **coluna a coluna**. Então uma coluna passa a ser escrevível — a que
registra a anulação — e todas as que dizem quanto foi pago continuam recusando `update` pelo banco.

**A imutabilidade não foi relaxada; ficou precisa.** O teste de RN-FIN-020 continua verde, e
`EstornoIT.grant_de_coluna_e_preciso` prova os dois lados: `estornado_em` aceita, e
`valor_repassado`, `saldo_anterior`, `movimento_do_periodo` e `periodo_fim` recusam.

## 11. A competência da anulação, e por que ela difere do estorno de comanda

Ver RN-FIN-021. O resumo: reabrir comanda é **fato novo de hoje**; anular uma linha errada é dizer
que ela **nunca deveria ter existido**.

E para o repasse a competência original é obrigatória, não preferível: RN-FIN-017 deu a ele uma
competência *escolhida* (o fim do período). Anulá-lo na data de hoje liberaria o período com a soma
acumulada até o fim dele ainda contando o repasse original — e refechar pagaria **zero**. A
liberação não liberaria nada, e o defeito só apareceria quando alguém tentasse refazer um mês.

## 12. Concorrência e idempotência

- O estorno de fechamento entra **no mesmo advisory lock** do fechamento: estornar e fechar
  disputam o mesmo saldo acumulado, e sem o lock um estorno concorrente poderia liberar o período
  depois da conferência e antes da gravação.
- `marcarEstornado` tem `where estornado_em is null` no próprio SQL: estornar duas vezes não
  sobrescreve a data do primeiro estorno, mesmo com duas requisições simultâneas.
- O estorno de lançamento é idempotente pelo razão, sem marca na linha original — quem já foi
  anulado tem uma linha apontando para si (`jaEstornado`).

## 13. Observabilidade

`log.warn` nos dois estornos, com id, período/competência, quantidade de linhas e motivo. Auditoria
com `FECHAMENTO_ESTORNADO` e `LANCAMENTO_ESTORNADO`, guardando o antes (valor, competência) e o
depois (motivo) — a trilha precisa responder "quem desfez o pagamento da Ana, e por quê" meses
depois.

## 14. UX e front

Sem tela. Estorno é ação rara com efeito financeiro: quando a tela existir, ela cai sob R-UX-05 —
confirmação explícita com o efeito descrito em número ("isso devolve R$ 60,00 à conta da Ana").

## 15. Testes obrigatórios

- [x] `EstornoIT` — 13 cenários: **a dívida volta na competência do período** · **período estornado
  é refechável e paga o mesmo valor** · o documento fica, com o valor pago intocado · estorno
  idempotente sem sobrescrever a data · estornado não conta como último período fechado · motivo
  obrigatório · 404 em id inexistente · **anula uma linha só, as outras ficam de pé** · a anulação
  herda a competência · **um estorno não se estorna** · estorno de linha idempotente · sem
  `financeiro:close` é recusado · **o `GRANT` de coluna é preciso: `estornado_em` aceita e os
  valores pagos recusam**.
- [x] Os 95 testes de integração do módulo `financeiro` continuam verdes.

## 16. Como testar manualmente

1. Feche um período com uma comissão de R$ 60. O extrato do período zera.
2. Estorne o fechamento com motivo. O extrato **do mesmo período** volta a R$ 60.
3. Feche o mesmo período de novo: deve criar documento novo e pagar R$ 60.
4. Consulte a lista: os dois documentos aparecem, o primeiro com `estornadoEm`.
5. Estorne uma comissão isolada e confira que a linha vizinha sobreviveu.
6. Tente estornar o `ESTORNO`: deve recusar com 422.

## 17. Decisões e trade-offs

| Decisão | Alternativa descartada | Por quê |
|---|---|---|
| `GRANT UPDATE` por coluna | tabela separada de "período fechado" | resolve sem migração de dados nem nova tabela, e preserva a constraint no banco |
| documento estornado fica | apagar a linha | o registro é o que explica o dinheiro que saiu |
| anulação na competência original | sempre hoje | senão refechar um período liberado paga zero |
| `estornarOrigem` mantém "hoje" | unificar as duas | reabrir comanda é fato novo; anular linha errada não é |
| recusar estorno de estorno | aceitar e reaplicar | reaplicar dinheiro não pode ser efeito de clique repetido |
| `financeiro:close` | permissão nova | mesmo grau de poder; separar quando algum salão pedir |

## 18. Pendências

- [ ] **Não existe lançamento manual (`AJUSTE`).** `TipoDeLancamento.AJUSTE` e
      `origem_tipo = 'MANUAL'` existem e nada os escreve — então "reaplicar o valor com motivo",
      que RN-FIN-023 oferece como caminho, ainda não tem rota. É a próxima peça natural.
- [ ] **Estornar um fechamento não avisa quem recebeu.** O profissional descobre no extrato.
- [ ] **Não há tela**, e o estorno é a ação que mais precisa de confirmação com número.
- [ ] **O estorno de fechamento não reverte o dinheiro que saiu do banco.** Ele devolve a dívida;
      se o valor não voltou de fato, o registro correto é um vale.
- [ ] Rota HTTP dos dois estornos sem teste de borda (a cobertura é de caso de uso).
