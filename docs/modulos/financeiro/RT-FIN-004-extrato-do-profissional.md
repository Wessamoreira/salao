---
id: RT-FIN-004
titulo: Extrato do profissional, item a item
modulo: financeiro
fase: 2
perfil: completo
status: implementado
depende_de: [RT-FIN-003]
permissoes: [financeiro:read:all, financeiro:read:own]
eventos: []
regras: [RN-FIN-011]
atualizado_em: 2026-09-05
---

# RT-FIN-004 — Extrato do profissional

## 1. Objetivo

Encerrar a conversa *"recebi menos esse mês"*.

## 2. Item a item, e não só o total

Um total sozinho não encerra nada: ele obriga a pessoa a **acreditar**. A lista mostra cada comanda,
o serviço, o percentual e de qual regra ele veio — e quem carrega isso é a descrição gravada no
lançamento (RT-FIN-003), sem consultar regra nenhuma. Inclusive depois que a regra deixar de
existir.

**O estorno aparece como linha**, não como subtração invisível. Uma comanda reaberta e refechada
deixa três lançamentos na conta da pessoa — comissão, estorno, comissão — e esconder dois faria o
total não bater com nada que ela consiga conferir contra as próprias comandas.

## 3. O sinal é invertido uma vez, aqui

No razão, comissão é **negativa**: ela sai do salão. Na conta da pessoa, ela **entra**. O extrato
inverte uma vez, no ponto de leitura, para que ninguém precise lembrar disso ao ler a tela.

> **Correção de uma frase de RT-FIN-003.** Escrevi lá que o sinal permite ao salão ficar com 140
> "sem ninguém precisar saber quais tipos somam", como se a soma global do razão fosse o resultado
> do salão. Isso vale **só enquanto vale e repasse não existem**: os dois são movimento de caixa,
> não resultado, e entram na conta do profissional reduzindo a dívida. A partir de RT-FIN-005 a
> soma global deixa de significar lucro — o que continua valendo é o que RN-FIN-008 já dizia:
> **saldo é sempre soma filtrada**, por contraparte.

## 4. O período é obrigatório

Um padrão de "mês corrente" pareceria conveniente e seria a fonte da conversa mais cara deste
sistema: duas pessoas olhando telas com períodos diferentes e concluindo que uma delas está errada.

## 5. `financeiro:read:own` ainda não funciona

Ver o próprio extrato exige saber **qual profissional** é o usuário logado — e `usuario` não tem
`profissional_id`.

É a mesma lacuna que impede `agenda:read:own` nas doze rotinas da agenda, e resolvê-la é rotina do
`iam`. Enquanto o vínculo não existe, a permissão própria falha fechada: ela não aceita um
`profissionalId` escolhido pelo cliente e não expõe salário alheio.

## 5b. Um campo mudou de nome em RT-FIN-005

`LinhaDoExtrato.comandaId` virou o par `origemTipo` / `origemId`. O nome estava certo enquanto
comanda era a única origem possível; com o vale, um id de vale guardado num campo chamado "comanda"
é o tipo de coisa que ninguém percebe até a tela abrir a comanda errada.

## 6. Testes

- [x] `ExtratoDoProfissionalIT` — 9: **a comissão aparece positiva, invertida uma vez** · cada linha
      diz de qual comanda veio, com serviço e percentual · dois atendimentos somam · **o estorno
      aparece como linha** · **reaberta e refechada: três linhas, total de um atendimento** · fora
      do período não entra · o extrato de uma pessoa não traz o da outra · período invertido é
      recusado · um salão não vê o extrato do outro

## 7. Pendências

- [ ] **`financeiro:read:own` ainda não entrega o próprio extrato.** O acesso falha fechado até o
      `iam` vincular usuário e profissional; ninguém escolhe um id e lê salário alheio
- [x] ~~**Não há total por tipo.**~~ Resolvida em RT-FIN-005: o extrato traz `totais`, uma linha
      por tipo. Virou necessária no mesmo dia em que o vale passou a entrar na conta — RN-FIN-015
- [x] ~~**Vale não existe.**~~ Entregue em RT-FIN-005: as linhas de `VALE` entram neste extrato e a
      conta passou a ser "a receber **menos o que já foi adiantado**". `REPASSE` continua faltando
      (RT-FIN-006)
- [ ] **Não há tela.** A rota existe e o front não a usa — e é a tela que o profissional mais quer
- [ ] **Sem paginação.** Um mês de salão movimentado passa de cem linhas; a rotina devolve todas
- [ ] **Não há exportação.** O repasse é conferido no papel, e não há como imprimir
