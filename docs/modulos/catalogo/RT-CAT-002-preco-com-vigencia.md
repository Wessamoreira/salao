---
id: RT-CAT-002
titulo: Preço com vigência + histórico
modulo: catalogo
fase: 1
perfil: completo
status: implementado
depende_de: [RT-CAT-001]
permissoes: [config:manage, agenda:read:all]
eventos: []
regras: [RN-CAT-005, RN-CAT-006, RN-CAT-007, RN-CAT-008]
atualizado_em: 2026-08-30
---

# RT-CAT-002 — Preço com vigência

## 1. Objetivo

Guardar quanto o serviço custa **e quanto custava**. A rotina inteira existe por uma frase: *o
passado não pode mudar*.

## 2. O defeito que ela evita

Com o preço numa coluna de `servico`, subir a escova de R$60 para R$70 **reescreve o passado**: o
relatório de fevereiro passa a dizer 70, e a comissão já paga deixa de bater com o extrato.

Nada quebra. Nenhum erro aparece. O salão só descobre quando um profissional confere o extrato
antigo e os números não fecham — e aí ninguém sabe explicar por quê.

## 3. Só a data de início, nunca a de fim

Cada preço é uma linha com `vigente_de`. Não existe `vigente_ate` (RN-CAT-005).

O modelo com data de fim obriga a manter **duas linhas coerentes**: ao cadastrar o preço novo, é
preciso fechar o anterior. Uma falha no meio disso deixa dois preços vigentes ao mesmo tempo, ou
nenhum — e a consulta passa a depender de qual linha o banco devolve primeiro.

Sem `vigente_ate`, "o preço em D" é o de maior `vigente_de` que já começou. **A coerência vira
consequência da consulta, não disciplina de escrita.**

Sai de graça dessa escolha: preço futuro. "A partir de 1º de abril, R$70" cadastrado em março
funciona sem tratamento especial — e anunciar o aumento antes de aplicá-lo é o que um salão faz.

## 4. Vazio, nunca zero

Serviço sem preço até a data devolve `Optional.empty()` (RN-CAT-007).

Zero **é um preço**. Um serviço sem preço num relatório financeiro precisa aparecer como pendência
de cadastro, não como brinde — e devolver zero faria a soma fechar com o número errado, que é pior
do que não fechar.

Pelo mesmo motivo `precoEm` sempre exige a data: pedir "o preço" sem dizer quando é a pergunta que
produz o relatório errado.

## 5. Retroativo é permitido, e vigiado

Recusar preço retroativo obrigaria o salão a conviver com um erro de digitação — *"lancei 700 em
vez de 70, ontem"*.

Então ele passa, e vai para a trilha **marcado como retroativo** (RN-CAT-008).

**Mas a proteção de verdade não está aqui.** Ela é `atendimento` copiar o preço para o item da
comanda no momento do atendimento (RN-CAT-006). Com essa cópia, nenhuma alteração de preço alcança
o que já foi feito — nem retroativa, nem por engano, nem de propósito.

É a segunda vez que este projeto registra uma obrigação assim para a Fase 2; a primeira foi
RN-IAM-017, sobre a base de comissão. As duas têm a mesma forma: *o fechamento precisa guardar a
regra que usou*.

## 6. Só o futuro se apaga

Um preço anunciado para o mês que vem e depois cancelado **nunca valeu para ninguém**: apagá-lo não
muda nada que aconteceu.

Um preço que já começou é outra coisa. Apagá-lo faria o serviço voltar ao valor anterior
retroativamente — o relatório do mês passado mudaria sozinho. Quem errou um preço já vigente
corrige **cadastrando outro**, o que deixa rastro; apagar não deixaria.

É a terceira vez que a pergunta "apaga ou não apaga?" aparece, e a resposta continua sendo a mesma:
*apaga-se o que não sustenta nada*. Bloqueio de jornada apaga (RT-EQP-003); cliente, profissional e
serviço não; preço vigente não, preço futuro sim.

## 7. "Hoje" é no fuso do salão

O servidor roda em UTC (ADR-0009). Depois das 21h no horário de Brasília, o dia em UTC já virou — e
um preço cadastrado "para hoje" nasceria retroativo, ou um retroativo passaria por futuro.

`relogio.hojeEm(fusoDoEstabelecimento)`, como manda a inviolável nº 3.

## 8. Testes

- [x] `PrecoDoServicoIT` — 14: **aumentar não reescreve o passado** · vigência inclusiva no dia
      exato · **preço futuro** · **vazio, nunca zero** · histórico completo em ordem · duas datas
      iguais recusadas · **retroativo marcado na trilha** · hoje não é retroativo · cancela preço
      futuro · **não apaga preço vigente** · preço negativo · serviço inexistente · isolamento
      entre salões · a recepção lê mas não define

## 9. Um teste que testava a formatação

A primeira versão das asserções de trilha comparava o **texto** do `jsonb` procurando
`"retroativo":true`. O Postgres renderiza com espaço depois do dois-pontos, e o teste falhou com o
comportamento correto.

Consertado consultando o campo (`depois->>'retroativo'`) em vez do texto. Teste preso à formatação
de serialização quebra quando nada de importante mudou — e o pior é que ele também *passaria* se a
formatação por acaso coincidisse com um dado errado.

## 10. Pendências

- [ ] **`atendimento` precisa copiar o preço para o item da comanda** (RN-CAT-006). Sem isso, a
      trilha de retroativos é a única defesa do histórico financeiro. É obrigação da Fase 2 e está
      registrada nas regras para não depender de memória
- [ ] **Preço por profissional não existe.** Cabeleireiro sênior cobrando mais que júnior é comum,
      e o plano prevê override de *duração* (RT-CAT-004) mas não de preço
- [ ] **Nada avisa quantos agendamentos futuros um aumento afeta.** Cliente com horário marcado
      para depois da virada vai pagar o preço novo, e o salão deveria saber disso ao cadastrar
- [ ] **Sem preço promocional por período** (semana da mãe, terça de manicure). Hoje seria dois
      cadastros manuais, e voltar ao preço antigo é fácil de esquecer
- [ ] **Não há tela**, como em toda a Fase 1
