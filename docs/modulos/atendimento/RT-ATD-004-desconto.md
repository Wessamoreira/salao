---
id: RT-ATD-004
titulo: Desconto com permissão e limite por perfil
modulo: atendimento
fase: 2
perfil: completo
status: implementado
depende_de: [RT-ATD-002, RT-IAM-007]
permissoes: [comanda:discount]
eventos: []
regras: [RN-ATD-011, RN-ATD-012, RN-ATD-013]
atualizado_em: 2026-09-05
---

# RT-ATD-004 — Desconto

## 1. Objetivo

Deixar dar desconto sem deixar qualquer um dar qualquer desconto.

## 2. O desconto é do item, e isso não é detalhe de modelagem

Um desconto no total da comanda teria de ser rateado entre os itens para chegar às comissões — e o
rateio decidiria, sozinho, **de quem** sai o desconto.

Numa comanda com corte da Ana e coloração da Bia, *"vinte reais de desconto"* tiraria dinheiro das
duas sem ninguém escolher. Aplicar no item obriga a escolha a ser feita por quem dá o desconto, que
é quem sabe por que está dando.

É atrito de propósito, no único lugar onde ele evita uma conta errada no bolso de outra pessoa.

## 3. Duas perguntas diferentes

| Pergunta | Quem responde |
|---|---|
| *Pode dar desconto?* | a permissão `comanda:discount` |
| *De quanto?* | o teto do usuário, que o `iam` calcula |

A recepção tem a permissão e para nos 10%. Separar as duas é o que permite dar a alçada sem dar o
poder inteiro — e é a diferença entre "não pode" e "não tanto".

**O `atendimento` não pergunta o perfil.** Ele pergunta o teto, por `LimitesDoUsuarioApi`. Saber que
alçada se decide por perfil o faria repetir, do lado de fora, a tabela que só o `iam` conhece — e a
segunda cópia é a que continua autorizando depois que a primeira mudar.

**E o perfil sai do banco, não do token.** O token é autocontido: emitido, vale até expirar, e o
próprio `EmissorDeTokenJwt` avisa que um perfil rebaixado no meio do expediente continua valendo no
token antigo. Para desenhar menu isso passa. Para autorizar dinheiro seria uma janela em que quem
acabou de perder a alçada ainda dá cem por cento — e a trilha registraria a permissão que a pessoa
já não tinha.

## 4. O teto é percentual mesmo quando o desconto é em dinheiro

Num item de R$ 80,00, "50%" e "R$ 40,00" são o mesmo desconto. Um limite que só olhasse o campo
percentual recusaria o primeiro e aceitaria o segundo — e o furo não precisaria de má-fé para ser
usado: bastaria a recepção digitar do jeito que ela já digita.

Por isso a conferência converte sempre para percentual do valor cheio do item, e é esse número que
aparece na recusa: *"desconto de 50,00% pedido; seu limite é 10%"*. Uma mensagem que diz só "acima
do limite" faz a pessoa tentar de novo às cegas.

**Sem identidade, o teto é zero.** Job agendado e consumidor de evento agem sem ninguém logado;
falha fechada é a única resposta segura — quem não tem rosto não dá desconto.

## 5. Quem paga o desconto é configuração, não código

`descontoAfetaComissao` responde a pergunta 2 de `13-perguntas-em-aberto`, e a resposta muda de
salão para salão:

| Configuração | Base da comissão | Quem paga o desconto |
|---|---|---|
| `false` (padrão) | valor cheio do serviço | o salão |
| `true` | total já descontado | salão e profissional |

O padrão é `false` porque é o que não surpreende: o profissional executou o serviço inteiro, e a
decisão comercial de baixar o preço foi de outra pessoa.

**O percentual continua snapshot.** Recalcular a comissão é refazer a *conta*, não a *regra*: o
percentual vem da própria linha, gravada no lançamento (RN-ATD-010). Reler a ficha do profissional
aqui apagaria o snapshot em silêncio — e o efeito só apareceria no repasse, meses depois.

## 6. Remover é a mesma operação

Zero é um desconto. `PUT` com zero remove, passa pelo mesmo teto e deixa a mesma trilha — não há
rota separada para desfazer, porque uma rota separada é uma segunda regra para manter em dia.

`PUT` e não `POST`: define o desconto da linha, não o acumula. Mandar duas vezes o mesmo valor
deixa a comanda no mesmo estado.

## 7. Testes

- [x] `AplicarDescontoIT` — 13: a recepção desconta até 10% · **a recepção não passa dos 10%** ·
      **o teto vale também em dinheiro** · o gerente desconta o item inteiro · **sem identidade o
      teto é zero** · por padrão o desconto sai do salão · **configurado o contrário, sai também da
      comissão** · **o percentual gravado não é relido da ficha** · zero remove · desconto maior
      que o item · desconto negativo é acréscimo · valor e percentual juntos, ou nenhum · comanda
      que não está aberta
- [x] `ComandasWebIT` — 14 (3 novos): desconto dentro do teto · **acima do teto o servidor recusa
      com 403, mesmo o front sabendo o limite** · valor e percentual juntos param na borda

## 8. Pendências

- [ ] **Não há desconto de comanda.** É a forma como o balcão fala ("tira 20 do total"), e ela
      precisa de rateio — `Money.ratearProporcionalmente` existe para isso. O que falta decidir não
      é o código: é se o rateio pode escolher sozinho de quem sai o desconto. RT-ATD-008
- [ ] **O teto não distingue tipo de desconto.** Cortesia integral para uma cliente antiga e 5% de
      simpatia passam pelo mesmo limite; o salão costuma tratar as duas coisas diferente
- [ ] **Nada limita o total descontado no dia.** Dez descontos de 10% cada, um por comanda, não
      disparam nada — o teto é por linha, e quem olha o acumulado é relatório que não existe
- [ ] **O motivo do desconto não é pedido.** A trilha registra quanto e quem, não por quê — e "por
      quê" é o que o dono pergunta quando vê o relatório
- [ ] **Não há tela.** `Capacidades` já entrega `descontoMaximoPercentual` ao front desde
      RT-IAM-006, e não há campo para usá-lo
