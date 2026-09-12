---
id: RT-FIN-005
titulo: Vale / adiantamento
modulo: financeiro
fase: 2
perfil: completo
status: implementado
depende_de: [RT-FIN-004]
permissoes: [financeiro:advance, financeiro:read:all, financeiro:read:own]
eventos: []
regras: [RN-FIN-012, RN-FIN-013, RN-FIN-014, RN-FIN-015]
atualizado_em: 2026-09-07
---

# RT-FIN-005 — Vale / adiantamento

## 1. Objetivo

Registrar o dinheiro entregue **antes** do fechamento, de forma que ele apareça no lugar onde vai
ser descontado — e não num caderno paralelo.

`vale não descontado` está nomeado no risco R-06 (*"fechamento financeiro que não bate"*) como uma
das quatro causas. As outras três já tinham resposta: snapshot de preço, `regra_origem` gravada e
rateio com o último item absorvendo o centavo. Esta é a quarta.

## 2. Duas escritas, uma transação

O `vale` guarda o **fato**: de quem, quanto, por qual meio o dinheiro saiu e por quê.
O razão guarda o **efeito**: menos a receber.

As duas commitam juntas. Um vale sem lançamento é dinheiro entregue que ninguém desconta; um
lançamento sem vale é um desconto que ninguém consegue explicar. A primeira é a que custa caro, e é
exatamente a que uma transação separada produziria.

**Por que a tabela existe, se o razão já guarda o efeito.** Porque o razão não responde *"saiu da
gaveta ou caiu na conta?"* — e é essa pergunta que faz o caixa do dia fechar ou não fechar. Um vale
de R$ 200 em dinheiro é R$ 200 que faltam na gaveta às 19h; o mesmo vale em PIX não é.

## 3. O sinal é positivo, e é isso que reduz o a receber

No razão, comissão é **negativa** — sai do salão. O vale é **positivo** na mesma conta: devolve ao
salão parte do que ele estava devendo. O extrato inverte tudo uma vez (RN-FIN-011), e é lá que o
vale aparece com o sinal de menos que a pessoa espera ver.

Uma consequência que já estava anunciada em RT-FIN-004 e agora vale de verdade:

> **A soma global do razão deixou de significar lucro.** O vale é movimento de caixa, não
> resultado. O que continua valendo é o que RN-FIN-008 sempre disse: **saldo é soma filtrada**, por
> contraparte. A frase foi corrigida no javadoc de `Razao` e em `04-modelo-de-dados`, onde ela
> estava escrita errada.

## 4. Não há teto, e o saldo negativo é legítimo

Adiantar mais do que a pessoa já ganhou é o que *adiantamento* significa. Recusar seria inventar
uma regra que o salão não tem — e a recepção contornaria registrando dois vales menores.

Comparar com um saldo aqui exigiria **escolher um período**, e escolher um período no lugar de quem
pergunta é exatamente o que RT-FIN-004 recusou fazer no extrato, com a razão que continua valendo:
duas pessoas olhando telas com períodos diferentes, cada uma concluindo que a outra está errada.

O saldo negativo atravessa para o período seguinte. Quem vai carregá-lo é RT-FIN-006.

## 5. Cancelar é estornar; a tabela não tem status

Vale cancelado é vale com um `ESTORNO` no razão apontando para ele. Não há coluna `cancelado`: ela
seria uma segunda verdade sobre o mesmo fato, e as duas divergiriam no primeiro caminho que
esquecesse de escrever numa delas. É a mesma forma da devolução de estoque (RN-ATD-022) e do
estorno de comanda (RN-FIN-010) — e a idempotência sai de graça junto.

Que o vale errado continue visível não é efeito colateral: *"por que a Ana recebeu R$ 300 em
março?"* precisa continuar tendo resposta depois de o registro ser desfeito, e um vale apagado
responderia "não recebeu".

Cancelar duas vezes devolve o mesmo estado, sem erro. Quem repete o pedido quer o mesmo resultado
final, e recusar obrigaria a tela a distinguir "não deu certo" de "já estava" — distinção que ela
erraria, porque o resultado é o mesmo.

## 6. `financeiro:advance` é permissão nova

Separada de `financeiro:close` porque são atos diferentes: fechar o período é apurar o que já
aconteceu; adiantar é **tirar dinheiro do caixa hoje**.

Hoje as duas caem exatamente nos mesmos perfis (ADMIN e GERENTE), e é justamente por isso que
separá-las custou uma constante e nenhuma migração. No dia em que um salão quiser que a recepção
adiante sem fechar o mês, a permissão já existe.

## 7. O extrato passou a abrir o total por tipo

Consequência direta desta rotina, e não escopo esticado: até RT-FIN-004, `aReceber` era "quanto a
pessoa ganhou". Agora é "quanto ganhou **menos o que já levou**".

Sem a abertura, quem ganhou R$ 600 e adiantou R$ 550 lê **R$ 50** e conclui que trabalhou por
R$ 50 — que é a mesma conversa que o extrato existe para encerrar, voltando pela outra porta.

**E um campo mudou de nome.** `LinhaDoExtrato.comandaId` virou o par `origemTipo` / `origemId`. O
nome antigo estava certo enquanto comanda era a única origem possível; com o vale, um id de vale
guardado num campo chamado "comanda" é o tipo de coisa que ninguém percebe até a tela abrir a
comanda errada.

## 8. A competência não vem de fora

O dia do salão, do relógio, no fuso do estabelecimento — a mesma regra da apuração (RN-FIN-010),
que agora tem **um lugar só**: a classe `Competencia`, usada pelo razão e pelo vale. Repetir a
linha nos dois seria repetir uma regra de enunciado único, e a segunda cópia é a que continua
usando o fuso errado depois que a primeira for corrigida.

Aceitar a data de quem chama pareceria flexível e abriria um buraco: um vale poderia ser empurrado
para dentro de um período já fechado, depois de o repasse ter sido pago.

## 9. Testes

- [x] `ValeIT` — 21: **o vale reduz o a receber** (60 − 50 = 10) · o extrato abre o total por tipo ·
      a linha se explica sozinha (forma e motivo na descrição) · vale sem motivo não inventa um ·
      **adiantar mais do que ganhou deixa o saldo negativo, e é permitido** · cancelar devolve o
      saldo e o vale continua marcado · **cancelar duas vezes não estorna duas vezes** · cancelar
      exige motivo · cancelar inexistente é 404 · valor zero recusado no caso de uso · **e também
      pelo banco** (`vale_valor_check`) · forma obrigatória · um salão não adianta para o
      profissional do outro · **sem `financeiro:advance`, adiantar é recusado** · a lista marca o
      cancelado sem escondê-lo · sem profissional, a lista traz o salão inteiro · período invertido
      recusado · um salão não lista o vale do outro · **`update` e `delete` em `vale` recusados
      pelo banco** · **a mesma `Idempotency-Key` não entrega o dinheiro duas vezes**

Os 70 testes de integração já existentes do módulo continuam verdes depois da extração de
`Competencia` do `Razao`.

## 10. Pendências

- [ ] **Não há tela.** As rotas existem e o front não as usa. A tela natural do vale é a do extrato
      (pendência de RT-FIN-004), porque é lá que ele precisa aparecer para fazer sentido
- [ ] **`financeiro:read:own` ainda não entrega os próprios vales.** A permissão falha fechada até
      existir o vínculo `usuario` → `profissional`; não aceita um id escolhido pelo cliente
- [ ] **O vale não sabe de período fechado.** Enquanto RT-FIN-006 não existe, nada impede registrar
      um vale de hoje depois de o repasse do mês ter sido pago. A competência não retroagir já
      limita o estrago a um dia, mas não o elimina
- [ ] **Não há limite nem alerta.** Adiantar cinco mil para quem ganhou cem passa em silêncio. Um
      teto configurável por salão é decisão do dono, e não há tela de configuração para pedi-lo
- [ ] **Não há caixa.** "Saiu da gaveta" está registrado na `forma`, mas não existe conferência de
      caixa contra a qual bater — é da Fase 5
- [ ] **Sem paginação na lista.** Mesma pendência do extrato, e pelo mesmo motivo
- [ ] **`ValesController` não tem teste de rota.** A cobertura é de caso de uso, como em RT-FIN-004;
      a resposta com nome do profissional e o cabeçalho `Idempotency-Key` não têm teste de borda
