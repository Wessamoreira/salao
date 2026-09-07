---
id: RT-FIN-002
titulo: Regra de comissão em cascata + snapshot no executante
modulo: financeiro
fase: 2
perfil: completo
status: implementado
depende_de: [RT-ATD-002, RT-EQP-001]
permissoes: [config:manage, financeiro:read:all]
eventos: []
regras: [RN-FIN-005, RN-FIN-006, RN-FIN-007]
atualizado_em: 2026-09-05
---

# RT-FIN-002 — Cascata de comissão

## 1. Objetivo

Responder *"quanto esta pessoa ganha por este serviço"* — e guardar **por que**.

## 2. A rotina começou desfazendo uma coerção

`comissao_percentual` era `not null default 0`, e o cadastro convertia nulo em zero. Com isso todo
profissional tem percentual, e o nível "padrão do salão" — logo abaixo da ficha na cascata — nunca
alcançaria ninguém. **A cascata nasceria decorativa.**

É a mesma distinção que este projeto já fez três vezes por outros motivos:

| Onde | A frase |
|---|---|
| `ServicoApi.precoEm` (RN-ATD-005) | *"zero é um preço"* |
| `pagamento.taxa_prevista` (RN-FIN-004) | *"zero seria taxa isenta"* |
| `SaldoDoProduto.custoMedio` (RN-EST-010) | *"zero significaria unidades que não custaram nada"* |

Aqui: **zero é uma comissão** — a de quem trabalha por salário fixo. "Ninguém configurou" precisa
ser outra coisa, e agora é nulo.

As linhas existentes **não** foram convertidas. Um zero gravado pode ter sido deliberado, e
reescrever intenção alheia é pior do que deixar o campo como está.

## 3. A ordem, e o ponto discutível

1. **Profissional × serviço** — *"a Ana faz coloração por 45%"*
2. **Serviço** — *"coloração paga 30%, o material é caro"*
3. **Ficha do profissional** — *"a Ana ganha 40% em tudo"*
4. **Padrão do salão** — *"a casa paga 30%"*

O ponto discutível é o **serviço vir antes da ficha**, e a razão é dupla:

- Sem isso o nível seria decoração. Toda profissional tem percentual na ficha, então uma regra por
  serviço só alcançaria quem estivesse com o campo vazio.
- Uma regra de serviço existe para proteger a margem de um serviço caro — e é justamente nos
  serviços caros que trabalham as pessoas com percentual alto. Se a ficha vencesse, a proteção
  falharia exatamente para quem ela precisa alcançar.

Quem quiser manter os 45% da Ana na coloração tem o nível mais específico, que existe para isso.

## 4. Nada configurado devolve zero **dizendo que é por falta de regra**

`SEM_REGRA` é diferente de um zero configurado, que tem origem `PROFISSIONAL` ou
`PADRAO_DO_SALAO`. Sem a distinção, *"por que não recebi comissão?"* teria a mesma resposta para a
decisão do salão e para o cadastro esquecido.

## 5. O enum mudou de módulo junto com a regra

`OrigemDaComissao` nasceu em `atendimento` (RT-ATD-002) com dois valores, quando a cascata não
existia. Agora quem decide o percentual é o `financeiro`, e **o enum pertence a quem decide**.

Pela mesma razão, `AdicionarItemDeServicoUseCase` parou de ler a ficha do profissional: ler a ficha
do lado do atendimento reescreveria, fora da fronteira, a regra que só o financeiro conhece — e a
segunda cópia é a que continua pagando errado depois que a primeira mudar.

## 6. O snapshot é o que faz a pergunta ter resposta

Percentual e origem viram snapshot no executante quando o item é lançado (RN-ATD-010). Remover a
regra depois **não** muda comissão nenhuma já lançada.

É exatamente essa separação que faz *"por que a minha comissão foi 30%?"* continuar tendo resposta
depois que a regra deixou de existir — e há teste que remove a regra e confere que a linha antiga
não se mexe.

## 7. A precedência está escrita duas vezes, e há teste para isso

Ela aparece em texto no `order by` da consulta e em número no `EscopoDaRegra.peso()`. Duplicação
que diverge em silêncio: um teste compara as duas, comparando a ordem que o banco devolve com os
pesos do enum.

## 8. Quem pode

| Ato | Permissão | Quem tem |
|---|---|---|
| Definir e remover regra | `config:manage` | só o dono |
| Ler as regras | `financeiro:read:all` | dono e gerente |

Comissão é cláusula de contrato com quem trabalha no salão, não parâmetro operacional. Nem a
recepção nem o gerente a mudam — o gerente opera o dia e vê o financeiro; quem decide quanto cada
um ganha é quem contrata.

## 9. Testes

- [x] `CascataDeComissaoIT` — 17: **sem nada configurado, zero por falta de regra** · o padrão da
      casa · a ficha vence o padrão · **a regra do serviço vence a ficha** · o par vence tudo · o
      par não alcança outra pessoa nem outro serviço · **zero configurado não é o mesmo que sem
      regra** · definir de novo substitui · remover cai um nível · a lista vem na ordem da cascata ·
      **a ordem do SQL bate com o peso do enum** · escopo SALAO não leva ids · serviço de outro
      salão é recusado · percentual fora da faixa · um salão não usa a regra do outro · **o item
      lançado grava o percentual da cascata** · **mudar a regra depois não mexe no que foi lançado**

> **Um ponto que assumia não-nulo** apareceu ao rodar: a auditoria do cadastro de profissional
> fazia `comissao.toPlainString()`. Dez testes quebraram nele — e a correção não foi só evitar o
> NPE: a trilha precisa registrar "null", porque "0" ali seria a mesma mentira que a coerção.

## 10. Pendências

- [ ] **Produto não entra na cascata.** A pergunta 3 fala em "nível serviço/produto", e o modelo só
      tem serviço — porque item de produto não tem executante (RN-ATD-018). Os dois andam juntos:
      no dia em que produto pagar comissão, é preciso registrar quem vendeu
- [ ] **O assistente não usa a cascata.** RT-ATD-005 grava `INFORMADA_NO_ITEM`, e a cascata não tem
      dimensão de papel — "assistente ganha 10% do que o principal ganha" não é expressável
- [ ] **Não há vigência.** Mudar a regra vale a partir de agora; não há "a partir do mês que vem",
      e o preço de serviço (RT-CAT-002) tem. A assimetria é do plano
- [ ] **Nada avisa quem ficou sem regra.** `SEM_REGRA` é registrado no executante e não existe
      relatório que o procure — é a pendência que faz o cadastro esquecido só aparecer no extrato
- [ ] **Não há tela.** As rotas existem, inclusive a de simulação, e o front não as usa
