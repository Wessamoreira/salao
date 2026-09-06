---
id: RT-ATD-001
titulo: Abrir comanda (do agendamento ou avulsa)
modulo: atendimento
fase: 2
perfil: completo
status: implementado
depende_de: [RT-AGD-002, RT-CLI-001]
permissoes: [comanda:open, comanda:close]
eventos: []
regras: [RN-ATD-001, RN-ATD-002, RN-ATD-003, RN-ATD-004]
atualizado_em: 2026-09-05
---

# RT-ATD-001 — Abrir comanda

## 1. Objetivo

Abrir a conta de quem chegou. É o documento em que os serviços viram dinheiro e de onde sai a
comissão — aqui ele nasce vazio; item, desconto e pagamento chegam nas rotinas seguintes.

## 2. Abrir é idempotente por cliente

Se a cliente já tem comanda aberta, ela é **devolvida**, não duplicada.

Não é conveniência de implementação. Duas comandas abertas para a mesma pessoa é o defeito que
ninguém percebe na hora: metade dos itens cai numa, metade na outra, e o salão cobra uma só. O erro
aparece no fechamento do mês, quando já não há como saber o que faltou.

E é o que o salão faz de qualquer forma. Quem marcou corte às 9h e coloração às 14h paga **uma**
conta. Por isso o segundo agendamento do dia não abre a segunda comanda: cai na que já está aberta.

**Quem garante é o índice**, não o `if`: um índice único parcial em `(estabelecimento_id,
cliente_id) where status = 'ABERTA'`. O caso de uso consulta antes por educação, e entre a consulta
e o `insert` cabe outra transação — que é justamente o que acontece na hora do movimento, com duas
pessoas no balcão abrindo a comanda da mesma cliente. Há teste que pula a consulta de propósito
para provar que quem diz não é o banco.

A resposta traz `jaEstavaAberta`. Sem essa bandeira a tela anunciaria "comanda 42 aberta" para uma
comanda que está aberta desde as nove da manhã, com itens dentro — e no HTTP a diferença aparece
como **200 em vez de 201**: nada foi criado.

## 3. O número é do salão

`numero` é o que a recepção fala em voz alta: *"a comanda 42 da Maria"*. Ninguém grita um uuid.

Não é uma `sequence` do banco: sequence é global, o 42 de um salão gastaria o 42 do outro, e o
tamanho do salto contaria ao vizinho quantas comandas cada um abre. Também não é `max(numero) + 1`,
que duas recepcionistas simultâneas leem igual.

É uma linha por salão, incrementada em **um comando só**:

```sql
insert into contador_de_comanda (estabelecimento_id, proximo) values (:salao, 1)
on conflict (estabelecimento_id) do update set proximo = contador_de_comanda.proximo + 1
returning proximo
```

O lock é da linha do próprio salão, e o incremento vive na transação da abertura — um rollback
devolve o número. O preço é serializar as aberturas de um mesmo salão, que é exatamente o que
"numeração sem buracos" custa. Um salão abre dezenas de comandas por dia, não milhares por segundo.

## 4. Duas portas, um caminho

`doAgendamento` e `avulsa` chegam ao mesmo lugar por um método **privado**. Fosse público, seria
uma terceira porta sem `@PreAuthorize` — e chamada de dentro da própria classe ela nem passaria
pelo proxy do Spring. É o defeito que RT-CAT-004 encontrou e que `ArquiteturaTest` passou a
proibir.

## 5. Cancelado não abre; no-show abre

**Cancelado, não.** A comanda diria que aconteceu um atendimento que o próprio sistema registrou
como desmarcado, e é dessa contradição que sai o faturamento que não bate com a agenda.

**No-show, sim.** Se a pessoa está no balcão, o no-show é que estava errado. Recusar obrigaria a
recepção a corrigir o status antes de poder atender alguém que já está sentado na cadeira — e o
sistema estaria discutindo com a realidade em vez de registrá-la.

O que **não** acontece é a correção automática do status: `NO_SHOW` é terminal em RN-AGD-007, e
desfazer terminal é decisão da agenda, não do atendimento. Está registrado no `_modulo.md` da
agenda, junto com o outro ponto travado — quem marcou pelo site chega ao salão ainda `SOLICITADO`,
e "chegar" deveria confirmar.

## 6. A origem é informação, não vínculo

`origem` responde a pergunta do dono: *quanto do faturamento entra sem hora marcada?*

Ela **não** liga a comanda a um agendamento. Não poderia: a comanda aberta pelo horário das 9h
recebe também o serviço das 14h. Qual item veio de qual agendamento é do item (RT-ATD-002), onde a
relação é de fato um-para-um.

## 7. A barra do balcão

*"Comanda aberta esquecida é dinheiro que sai pela porta"* (R-UX-07). A lista vem da mais antiga
para a mais nova, porque a que está lá desde as nove da manhã é a que interessa.

Os nomes vêm em **uma** consulta, pela `ClienteApi.nomesDe` — que existe exatamente para isto.
Buscar cliente a cliente seria uma consulta por linha para desenhar uma barra que fica aberta o dia
inteiro.

## 8. Testes

- [x] `AbrirComandaIT` — 13: abre avulsa e numera do 1 · do agendamento, a comanda é da cliente do
      horário · o número anda sem buraco · **abrir de novo devolve a MESMA comanda** · **o segundo
      horário do dia cai na comanda que já está aberta** · **quem recusa a segunda é o índice, não
      a consulta** · cancelado não abre · **no-show abre** · cliente inexistente · agendamento de
      outro salão não existe para este · **cada salão numera do 1** · um salão não vê a comanda do
      outro · a barra lista com o nome, da mais antiga para a mais nova
- [x] `ComandasWebIT` — 7: 201 com Location · **reabrir responde 200, não 201** · os dois campos
      juntos são recusados na borda · nenhum dos dois também · a barra traz o nome · **o
      profissional não abre comanda** · sem token o balcão não abre

## 9. Pendências

- [ ] **O agendamento não vai para `EM_ATENDIMENTO`.** É o que o `_modulo.md` da agenda planejou
      para este momento, e está travado por RN-AGD-007 nos dois lados: `SOLICITADO` não transiciona
      para `EM_ATENDIMENTO` (chegar em pessoa deveria confirmar, e confirmar é RT-AGD-004), e
      `NO_SHOW` é terminal. Mexer nisso é rotina da agenda
- [ ] **Não há `comanda:read`.** A barra usa `comanda:open or comanda:close` porque o mapa de
      permissões não tem leitura de comanda. O profissional, que não abre nem fecha, não vê nem a
      própria comanda — e vai precisar ver quando o extrato existir (RT-FIN-004)
- [ ] **Fechar não existe.** Uma comanda aberta hoje fica aberta para sempre: `FECHADA` e
      `ESTORNADA` estão no enum e no `check`, e nenhuma rotina as alcança ainda (RT-ATD-006/007)
- [ ] **Os três valores são zero e ninguém os escreve.** `valor_bruto`, `valor_desconto` e
      `valor_liquido` existem desde já para o contrato da tela não mudar quando os itens chegarem
      (RT-ATD-002) — até lá, a barra mostra R$ 0,00 para todo mundo
- [ ] **Nada é publicado.** `ComandaAberta` não é evento: nenhum módulo o consumiria hoje, e evento
      sem consumidor é peso morto. O painel do balcão (RT-AGD-012) é o primeiro candidato
- [ ] **`versao` não é usada.** A coluna existe para o bloqueio otimista do fechamento; abrir não
      concorre com nada que a leia
- [ ] **Não há tela.** A barra de comandas abertas de R-UX-07 tem rota e não tem front
- [ ] **Comanda esquecida não avisa ninguém.** R-UX-07 pede alerta visual passadas 3 horas; o dado
      para isso é `aberta_em`, e quem decide o alerta é o front que ainda não existe
