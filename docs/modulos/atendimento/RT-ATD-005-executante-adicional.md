---
id: RT-ATD-005
titulo: Executante adicional (assistente) com percentual próprio
modulo: atendimento
fase: 2
perfil: completo
status: implementado
depende_de: [RT-ATD-002]
permissoes: [comanda:open]
eventos: []
regras: [RN-ATD-014, RN-ATD-015, RN-ATD-016]
atualizado_em: 2026-09-05
---

# RT-ATD-005 — Executante adicional

## 1. Objetivo

Pagar quem lavou o cabelo. É a pergunta 6 de `13-perguntas-em-aberto`, e o modelo já a previa: uma
linha a mais no item, com percentual próprio.

## 2. Uma linha a mais no item, não um item a mais

A alternativa — lançar "lavagem" como serviço separado — inventaria faturamento que não existe: a
cliente pagou uma coloração, não uma coloração e uma lavagem. O total da comanda ficaria maior que
o que ela pagou, e o relatório de faturamento por serviço passaria a contar lavagens que ninguém
vendeu.

## 3. O assistente **não** precisa da habilidade

RN-ATD-007 exige que o executante principal execute o serviço. Aqui não, e a diferença é o que o
campo `papel` existe para registrar: quem lava o cabelo não está executando a coloração — está
ajudando quem executa.

Exigir a habilidade obrigaria o salão a cadastrar toda assistente como colorista. E aí a agenda
passaria a oferecê-la para marcar coloração **sozinha** — o cadastro feito para pagar comissão
mudaria quem o sistema acha que pode atender.

## 4. O percentual é digitado, e a origem diz isso em voz alta

Não vem da ficha do assistente: quem lava também pode ser cabeleireira, e os 45% dela como
cabeleireira não têm nada a ver com o que ela ganha por lavar.

E não vem de configuração porque a pergunta 6 continua **sem resposta do dono** — *qual é o
percentual usual do assistente?* Enquanto ela não tiver, quem responde é quem está no balcão, e
`regra_origem` grava `INFORMADA_NO_ITEM` para dizer isso.

Gravar `PROFISSIONAL` seria mentir que o número veio de um cadastro, e três meses depois ninguém
saberia por que aquele percentual era aquele — que é exatamente o que a coluna existe para evitar.

> Quando RT-FIN-002 trouxer a cascata, o número digitado passa a ter um padrão para herdar. A
> origem continua sendo a resposta honesta: `INFORMADA_NO_ITEM` quando alguém digitou, o nível da
> cascata quando ele veio de regra.

## 5. As comissões do item não passam de 100%

Não é preciosismo contábil. O jeito de chegar lá é digitar **90** onde se queria **9** — e o erro
não aparece na comanda, que fecha normalmente. Ele vira problema no fechamento do mês, quando o
repasse não bate com o faturamento e ninguém sabe qual das trezentas linhas está errada.

## 6. Um principal por item, agora garantido pelo banco

A regra valia desde RT-ATD-002 **só porque ninguém tinha escrito o segundo caminho**: o principal
nascia junto com o item, e não havia outra porta. Abrir a porta do assistente é o que torna
possível inserir um segundo principal — e por isso ela passou a ser um índice único parcial.

Dois principais no mesmo item pagam duas comissões cheias pelo mesmo trabalho, e as duas linhas
parecem certas olhando a comanda.

**E o principal não sai.** Item de serviço sem principal é um serviço que ninguém executou: ele
continuaria faturando e pararia de pagar alguém, sem nada acusar. Trocar quem executou é remover o
item e lançar de novo — quem executou é parte do que a linha afirma, não um campo dela.

## 7. Uma conta só, num lugar só

`inserirExecutante` passou a gravar base e comissão zeradas; quem as preenche é sempre
`recalcularComissoesDoItem`. A base depende de `descontoAfetaComissao` e essa fórmula já vivia no
SQL — calculá-la também no Java seria a mesma conta em dois lugares, que é o defeito que RN-ATD-008
existe para impedir uma camada acima.

RT-ATD-002 foi ajustada junto: ela também insere e recalcula, em vez de calcular a comissão do
principal por conta própria.

## 8. Testes

- [x] `AdicionarExecutanteIT` — 13: o assistente entra com percentual próprio sobre a mesma base ·
      **o percentual não vem da ficha dele, e a origem diz isso** · **o assistente dispensa a
      habilidade** · a mesma pessoa não entra duas vezes · nem o principal · **as comissões não
      passam de 100%** · percentual fora de 0 a 100 · profissional inativo · comanda que não está
      aberta · **produto não tem quem o execute** · remover devolve o item ao que era · **o
      principal não sai** · **quem recusa o segundo principal é o índice**
- [x] `AplicarDescontoIT` — 14 (1 novo): **o desconto alcança também a comissão do assistente** —
      o recálculo é do item, e uma conta que só refizesse a do principal deixaria o assistente com
      número velho
- [x] `ComandasWebIT` — 16 (2 novos): o assistente entra e sai pela mesma comanda · assistente sem
      percentual para na borda

## 9. Pendências

- [ ] **Não há teto para o percentual do assistente.** Dar 40% a quem lavou é dinheiro saindo, como
      um desconto — e desconto tem alçada (RN-ATD-012). Este não tem: qualquer um com
      `comanda:open` digita qualquer número até 100%
- [ ] **A pergunta 6 continua aberta.** *Qual é o percentual usual?* Sem a resposta do dono não há
      padrão a herdar, e cada item é digitado do zero
- [ ] **Não há um segundo assistente com papel próprio.** O enum tem dois papéis; um salão com
      lavagem e secagem por pessoas diferentes lança as duas como `ASSISTENTE`, e o extrato não
      distingue o que cada uma fez
- [ ] **Trocar o principal exige refazer o item.** É a decisão certa hoje, e custa caro na tela:
      a recepção que errou a pessoa perde o desconto e os assistentes junto
- [ ] **Não há tela.** As rotas existem e o front não as usa (RT-ATD-008)
