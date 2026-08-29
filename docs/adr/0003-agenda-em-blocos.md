# ADR-0003: Agendamento modelado como cabeça + blocos

- **Status:** aceita · **Data:** 2026-08-28
- **Rotinas afetadas:** RT-AGD-001 a RT-AGD-012, RT-CNV-005

## Contexto

O rascunho original definia o serviço com blocos `ATIVO → PAUSA → ATIVO`, liberando o profissional
durante a pausa da química para encaixe — e, ao mesmo tempo, uma exclusion constraint com **um**
`tstzrange` por agendamento.

As duas coisas não coexistem. Com um range único cobrindo o vão inteiro, o encaixe na pausa é
rejeitado pelo banco. A contradição só apareceria na implementação, quando o schema já estivesse
em produção.

Há um segundo fato que o modelo de janela única também não expressa: durante a química a cliente
continua ocupando a **cadeira**, mas não o **profissional**. São eixos de ocupação com janelas
diferentes dentro do mesmo agendamento.

## Opções consideradas

| Opção | Prós | Contras | Custo de reverter |
|---|---|---|---|
| Janela única | Schema simples; uma constraint | Impossibilita encaixe na pausa e ocupação diferenciada de recurso | — |
| Um agendamento por bloco, ligados por grupo | Constraint direta | Perde o agregado; cancelar vira operação em N linhas soltas; UI complexa | Alto |
| **Cabeça + blocos filhos** | Expressa pausa e eixos independentes; um agregado; constraint no bloco | `status` precisa ser desnormalizado no bloco | Altíssimo se mudar depois |

## Decisão

`agendamento` (cabeça, com span total para índice e listagem) + `agendamento_bloco` (janelas
concretas, com `profissional_id` e `recurso_id` anuláveis). As exclusion constraints vivem no
bloco. Bloqueios de agenda entram na mesma tabela com `agendamento_id` nulo, para haver **uma**
constraint e um único lugar onde errar.

## Consequências

**Positivas.** Encaixe na pausa funciona sem gambiarra. Cadeira ocupada durante a química é
expressável. Bloqueio e agendamento disputam a mesma constraint, o que elimina uma classe inteira
de bug ("bloqueei mas o sistema deixou agendar").

**Negativas, assumidas.** O `status` é duplicado no bloco porque o Postgres não aceita subquery em
`EXCLUDE`. Toda transição de status precisa atualizar os blocos na mesma transação — isso vira a
RN-AGD-006, fica no agregado (nunca em trigger) e tem teste dedicado. É o preço, e ele é conhecido.

Consultar disponibilidade também fica mais caro: agrega blocos em vez de ler um intervalo. Mitigado
pelo índice GIST em `(estabelecimento_id, periodo)`.

**Revisitar quando.** Nunca por conveniência. Só se o dono confirmar que nenhum serviço do salão
tem tempo de processamento — o que precisa ser verificado com serviços reais no marco de validação
nº 1 (risco R-07), antes de a grade ser construída.
