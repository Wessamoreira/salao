# Regras de negócio — módulo `atendimento`

> **RN-ATD-010** (snapshot obrigatório de preço, percentual e regra de origem) já está enunciada em
> [`04-modelo-de-dados`](../../04-modelo-de-dados.md) e pertence às rotinas que a implementam —
> RT-ATD-002 e RT-ATD-006. Não está aqui ainda porque nenhuma linha de código a sustenta.

---

### RN-ATD-001 — Uma comanda aberta por cliente; abrir de novo devolve a mesma

**Enunciado.** Um cliente tem no máximo **uma** comanda `ABERTA` por estabelecimento. Pedir para
abrir de novo devolve a que já existe, com `jaEstavaAberta = true`, e não é erro.

**Motivo.** Duas comandas abertas para a mesma pessoa é o defeito que ninguém percebe na hora:
metade dos itens cai numa, metade na outra, e o salão cobra uma só. Aparece no fechamento do mês,
quando já não dá para saber o que faltou.

E é o que o salão faz de qualquer forma — corte às 9h e coloração às 14h é uma conta só. Por isso o
segundo agendamento do dia não abre a segunda comanda.

A bandeira na resposta existe porque a tela precisa saber a diferença: anunciar "comanda 42 aberta"
para uma comanda que está aberta desde as nove da manhã, com itens dentro, é mentir para quem está
no balcão. No HTTP isso aparece como 200 em vez de 201.

**Onde é garantida.** Índice único parcial `uma_comanda_aberta_por_cliente` ·
`AbrirComandaUseCase.abrir` consulta antes, e o `on conflict` aponta o índice.
**Rotinas.** RT-ATD-001 · **Teste.** `AbrirComandaIT.abrir_duas_vezes_e_a_mesma`,
`AbrirComandaIT.segundo_horario_mesma_comanda`, `AbrirComandaIT.o_indice_e_que_decide`,
`ComandasWebIT.reabrir_responde_200`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-ATD-002 — O número da comanda é sequencial por estabelecimento, e sem buracos

**Enunciado.** `numero` é gerado por um contador **por estabelecimento**, incrementado na mesma
transação da abertura. Nunca por `sequence` do banco nem por `max(numero) + 1`.

**Motivo.** É o número que a recepção fala em voz alta — ninguém grita um uuid.

`sequence` é global ao banco: o 42 de um salão gastaria o 42 do outro, e o tamanho do salto entre
dois números contaria ao vizinho quantas comandas cada um abre. `max + 1` é lido igual por duas
recepcionistas simultâneas.

O incremento vive na transação da abertura, então um rollback devolve o número. O preço é
serializar as aberturas de um mesmo salão — que é o que "sem buracos" custa, e um salão abre
dezenas de comandas por dia.

**Onde é garantida.** `contador_de_comanda` + `ComandasJdbc.proximoNumero` ·
`unique (estabelecimento_id, numero)`.
**Rotinas.** RT-ATD-001 · **Teste.** `AbrirComandaIT.numeros_sequenciais`,
`AbrirComandaIT.numeracao_por_salao`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-ATD-003 — Agendamento cancelado não vira comanda; no-show vira

**Enunciado.** Abrir comanda a partir de um agendamento `CANCELADO` é recusado
(`ER-ATD-AGENDAMENTO_CANCELADO`). A partir de um `NO_SHOW`, é permitido.

**Motivo.** A comanda de um cancelado diria que aconteceu um atendimento que o próprio sistema
registrou como desmarcado — e é dessa contradição que sai o faturamento que não bate com a agenda.

O no-show é o contrário: se a pessoa está no balcão, a marcação é que estava errada. Recusar
obrigaria a recepção a corrigir o status antes de atender alguém já sentado na cadeira, e o sistema
estaria discutindo com a realidade em vez de registrá-la.

**Tensão registrada.** O status do agendamento **não** é corrigido automaticamente: `NO_SHOW` é
terminal em RN-AGD-007, e desfazer terminal é decisão da agenda. Ver o `_modulo.md` da agenda.

**Onde é garantida.** `AbrirComandaUseCase.doAgendamento`.
**Rotinas.** RT-ATD-001 · **Teste.** `AbrirComandaIT.cancelado_nao_abre`,
`AbrirComandaIT.no_show_abre`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-ATD-004 — A origem da comanda é informação, não vínculo

**Enunciado.** `origem` guarda se a comanda nasceu de um agendamento ou avulsa. Ela **não** liga a
comanda a um agendamento; essa relação é do item (RT-ATD-002).

**Motivo.** A comanda aberta pelo horário das 9h recebe também o serviço das 14h — um vínculo na
comanda seria uma mentira já no segundo item, e a alternativa (uma comanda por agendamento) é
justamente o que RN-ATD-001 impede.

A pergunta que `origem` responde é do dono: *quanto do faturamento entra sem hora marcada?*

**Onde é garantida.** `comanda.origem` (`check`), preenchida por qual das duas portas foi usada.
**Rotinas.** RT-ATD-001 · **Teste.** `AbrirComandaIT.abre_avulsa`,
`AbrirComandaIT.abre_do_agendamento`
**Configurável?** Não · **Origem.** 2026-09-05
