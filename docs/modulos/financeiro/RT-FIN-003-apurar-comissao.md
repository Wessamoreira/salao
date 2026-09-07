---
id: RT-FIN-003
titulo: Apurar comissão no fechamento da comanda
modulo: financeiro
fase: 2
perfil: completo
status: implementado
depende_de: [RT-FIN-002, RT-ATD-006]
permissoes: []
eventos: []
regras: [RN-FIN-008, RN-FIN-009, RN-FIN-010]
atualizado_em: 2026-09-05
---

# RT-FIN-003 — Apurar comissão

## 1. Objetivo

Transformar o que a comanda gerou em linhas de livro-razão: receita para o salão, comissão para
quem executou.

## 2. É uma chamada, não um ouvinte do evento — e isso corrige uma decisão minha

Apurar exige os itens e os executantes da comanda. Se o `financeiro` os buscasse, ele dependeria de
`atendimento` — que **já depende dele**, para perguntar quanto pagar (RT-FIN-002) e quanto falta
receber (RT-FIN-001). Seria ciclo, e o Spring Modulith reprovaria o build.

A saída não é enfeitar o evento: é o `atendimento` **trazer** o que apurar, e o `financeiro`
decidir em que linhas isso vira. O atendimento não sabe que comissão entra com sinal negativo nem
a que mês a linha pertence — e não deveria.

E assim a apuração acontece **dentro da transação do fechamento**, que é o que o plano pedia desde
o início: *"fechar comanda — transacional: estoque + pagamento + comissão + evento"*.

> **Correção.** Em RT-ATD-006 eu escrevi que "apurar a comissão é RT-FIN-003, que ouve o evento
> publicado aqui", e em RT-ATD-007 publiquei `ComandaReaberta` e `ComandaEstornada` justificando-os
> como o que tornaria a compensação possível. A compensação acabou sendo uma chamada direta, na
> mesma transação — **o mecanismo que eu previ não é o que foi usado**.
>
> Os três eventos continuam publicados e sem consumidor. O que os sustenta hoje é outra coisa: o
> comprovante (RT-FIN-008) precisa saber que um fechamento deixou de valer, e o `_modulo.md` da
> agenda declara consumir `ComandaFechada`. Se nenhuma das duas se concretizar, eles devem ser
> apagados — "evento sem consumidor é peso morto" continua valendo.

## 3. Receita positiva, comissão negativa

O sinal define débito e crédito. Uma coloração de R$ 200,00 com 30% vira **duas** linhas: `+200` de
receita e `−60` de comissão. O salão fica com 140, e "quanto sobrou" é `sum(valor)` sem lista de
tipos.

É a mesma decisão do movimento de estoque (RN-EST-007), pela mesma razão: se o tipo decidisse
entrada e saída, a primeira consulta que esquecesse um tipo novo daria um número plausível e errado.

**Saldo é sempre soma, nunca coluna** — como no estoque (RN-EST-009). Não existe "recalcular o
razão".

> **Corrigido em RT-FIN-004.** A frase acima sugere que a soma global do razão é o resultado do
> salão. Isso vale só enquanto `VALE` e `REPASSE` não existem: os dois são movimento de caixa, não
> resultado, e entram na conta do profissional reduzindo a dívida. A soma que sempre vale é a
> **filtrada por contraparte**, como RN-FIN-008 já dizia.

## 4. A competência é o dia do salão

A comanda fechada às 23h50 de 31 de março num salão em Manaus pertence a **março** — e o servidor,
em UTC, já está em 1º de abril.

Errar isso joga faturamento para o mês seguinte, e é o tipo de erro que só aparece quando o dono
compara com o caderno. Por isso a competência sai de `Relogio.hojeEm(fuso do estabelecimento)`
(ADR-0009), e quem a calcula é o razão — não o atendimento.

## 5. Imutabilidade não é convenção, é permissão

A role da aplicação recebe `INSERT` e `SELECT` no `lancamento`. Sem `UPDATE`, sem `DELETE`.

Um `update` numa linha do razão não é recusado por disciplina de quem escreve o código: é recusado
**pelo banco**, para quem escrever o código errado amanhã. Há dois testes que tentam, com a conexão
da aplicação, e conferem o `permission denied`.

É a mesma decisão da auditoria (V3), e é o que faz "estorno é linha nova" deixar de ser
recomendação.

## 6. Desfazer é anular, e é idempotente pelo próprio razão

Reabrir e estornar chamam `estornarOrigem`, que escreve linhas `ESTORNO` de sinal contrário
apontando para as originais. Depois disso, a soma da comanda volta a zero.

A idempotência não precisa de marca: uma linha já anulada tem outra apontando para si, e a consulta
de pendentes usa isso. É a mesma forma da devolução de estoque (RN-ATD-022) — e as duas nasceram da
mesma disciplina.

Fechar de novo depois de reabrir apura outra vez. O razão fica com seis linhas e soma o líquido
certo, porque nada foi apagado no caminho.

## 7. Comissão de zero não vira linha

Ela não move saldo e poluiria o extrato de quem trabalha por salário fixo. **Por que** foi zero
está no executante, com a origem que a cascata gravou (`PROFISSIONAL` ou `SEM_REGRA`).

E a descrição da linha de comissão carrega o percentual e a origem — é o que faz o extrato
responder *"por que 30%?"* sem consultar regra nenhuma, inclusive depois que a regra deixar de
existir.

## 8. Testes

- [x] `ApuracaoDeComissaoIT` — 11: **receita positiva e comissão negativa** · a descrição explica o
      percentual · comanda só de produto não gera comissão · **comissão de zero não vira linha** ·
      **a aplicação não tem update no razão** · nem delete · **reabrir anula sem apagar** · fechar
      de novo apura outra vez sem dobrar · estornar anula · **estornar duas vezes não anula duas
      vezes** · um salão não vê o razão do outro

## 9. Pendências

- [ ] **Não há extrato.** As linhas existem e ninguém as soma por profissional — é RT-FIN-004, a
      próxima
- [ ] **A taxa do adquirente não é lançada.** `TAXA_ADQUIRENTE` está no enum e ninguém a escreve:
      sem ela, o razão mostra faturamento, não caixa (RT-FIN-001 já listava)
- [ ] **A receita não se liga ao pagamento.** As linhas de receita têm origem `COMANDA`; a
      conciliação da Fase 5 vai precisar de linhas por `PAGAMENTO`
- [ ] **Comanda reaberta e refechada acumula seis linhas.** Está certo e fica ilegível numa tela de
      extrato ingênua — a de RT-FIN-004 precisa saber somar em vez de listar
- [ ] **Não há competência retroativa.** Apurar sempre usa o dia de hoje; uma comanda fechada com
      atraso entra no mês errado
- [ ] **Nada valida que o razão fecha.** "A soma das receitas menos comissões bate com os
      pagamentos" não é conferido em lugar nenhum — e é justamente o critério de pronto da Fase 2
