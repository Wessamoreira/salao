# Regras de negócio — módulo `atendimento`

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

---

### RN-ATD-005 — Serviço sem preço é recusado; zero não é preço

**Enunciado.** Lançar um serviço que não tem preço vigente na data é recusado
(`ER-ATD-SERVICO_SEM_PRECO`). Serviço desativado também (`ER-ATD-SERVICO_FORA_DO_CARDAPIO`).

**Motivo.** O contrato de `ServicoApi.precoEm` já dizia por quê: *"zero é um preço, e um serviço sem
preço num relatório financeiro precisa aparecer como pendência de cadastro, não como brinde"*.
Lançar zero transforma falha de cadastro em faturamento perdido silencioso — o pior tipo, porque a
comanda fecha certinho e o dinheiro não aparece em lugar nenhum.

Desativar é decisão do salão de não vender mais aquilo. Aceitar aqui faria a desativação valer só
para quem marca horário, e não para quem cobra.

**Onde é garantida.** `AdicionarItemDeServicoUseCase.executar`.
**Rotinas.** RT-ATD-002 · **Teste.** `AdicionarItemDeServicoIT.servico_sem_preco`,
`AdicionarItemDeServicoIT.servico_desativado`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-ATD-006 — Comanda que não está aberta não muda

**Enunciado.** Item só entra e só sai de comanda `ABERTA`. Comanda `FECHADA` ou `ESTORNADA` recusa
lançamento e remoção (`ER-ATD-COMANDA_NAO_ESTA_ABERTA`).

**Motivo.** É o que dá sentido ao fechamento. Se a comanda continuasse mudando depois de fechada, o
valor pago, a comissão apurada e o lançamento no razão passariam a discordar dela — e o registro
que existe para ser a verdade do atendimento viraria o único que não é.

Corrigir uma comanda fechada é estornar e reabrir (RT-ATD-007), com trilha, não editar em silêncio.

**Onde é garantida.** `AdicionarItemDeServicoUseCase.comandaAberta` · `RemoverItemUseCase.executar`.
**Rotinas.** RT-ATD-002 · **Teste.** `AdicionarItemDeServicoIT.comanda_fechada`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-ATD-007 — Quem executa precisa executar aquele serviço, e estar ativo

**Enunciado.** O executante de um item de serviço tem de estar ativo e ter a habilidade do serviço
(`ProfissionalApi.executa`). Senão, `ER-ATD-NAO_EXECUTA_O_SERVICO` ou
`ER-ATD-PROFISSIONAL_NAO_ENCONTRADO`.

**Motivo.** É a mesma exigência de RN-AGD-001, do outro lado do atendimento. Deixar passar
produziria comissão para alguém cuja regra para aquele serviço nunca foi configurada — o número
apareceria certo na tela e errado no repasse.

Quando o salão põe alguém para substituir, a habilidade é o cadastro que precisava mesmo ser
corrigido, e corrigi-lo é uma tela.

**Onde é garantida.** `AdicionarItemDeServicoUseCase.profissionalQueExecuta`.
**Rotinas.** RT-ATD-002 · **Teste.** `AdicionarItemDeServicoIT.nao_executa_o_servico`,
`AdicionarItemDeServicoIT.profissional_inativo`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-ATD-008 — Os totais da comanda são gravados, e somados num lugar só

**Enunciado.** `valor_bruto`, `valor_desconto` e `valor_liquido` são recalculados por uma consulta
no banco a cada mudança de item, e `versao` sobe junto. Não são derivados na leitura nem somados na
aplicação. Lançar ou remover um item devolve a **comanda inteira**.

**Motivo.** Somar na leitura daria resposta diferente depois que o preço do catálogo mudasse — o
mesmo problema de RN-ATD-010, uma camada acima.

E somar em dois lugares — na tela e na gravação — produz divergência no dia em que alguém arredondar
diferente. Devolver a comanda inteira é o que impede o front de somar por conta.

**Onde é garantida.** `ComandasJdbc.recalcularTotais` · a resposta de lançar e de remover.
**Rotinas.** RT-ATD-002 · **Teste.** `AdicionarItemDeServicoIT.dois_itens_somam`,
`AdicionarItemDeServicoIT.remover_item`, `ComandasWebIT.lancar_item_devolve_a_comanda`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-ATD-009 — Item removido é apagado; a trilha fica na auditoria

**Enunciado.** Remover um item apaga a linha (e os executantes, por cascata). O registro de
auditoria é gravado **antes** do `delete`, com descrição e valor do que saiu.

**Motivo.** Item lançado por engano não é fato do atendimento — é digitação. Uma comanda cheia de
linhas riscadas é pior de conferir do que uma comanda certa, e quem confere é a recepção com a
cliente esperando.

A trilha não se perde porque a auditoria é append-only e a role da aplicação não tem `UPDATE` nem
`DELETE` nela. Gravar depois do `delete` seria gravar o quê — a linha já não existe, e "o que saiu
da comanda" é justamente a pergunta que a trilha precisa responder.

**Onde é garantida.** `RemoverItemUseCase.executar` · `on delete cascade` em `executante`.
**Rotinas.** RT-ATD-002 · **Teste.** `AdicionarItemDeServicoIT.remover_item`,
`AdicionarItemDeServicoIT.remover_item_de_outra_comanda`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-ATD-010 — Snapshot obrigatório: preço, percentual e origem da regra

**Enunciado.** `descricao` e `preco_unitario` do item, e `percentual_comissao` e `regra_origem` do
executante, são gravados no lançamento e **nunca recalculados**.

**Motivo.** Sem isso, reprocessar um fechamento de três meses atrás produz número diferente do
repasse já pago — e não há como explicar a diferença ao profissional.

`regra_origem` existe para responder *"por que a minha comissão foi 35% e não 40%"*. É a pergunta
que chega depois que a regra mudou, e recalcular com a regra de hoje é justamente o que não
responde.

Enunciada desde [`04-modelo-de-dados`](../../04-modelo-de-dados.md); passa a valer em código a
partir de RT-ATD-002, e o fechamento (RT-ATD-006) é quem congela também os valores derivados —
`base_calculo` e `valor_comissao`, que enquanto a comanda está aberta acompanham o desconto.

**Onde é garantida.** `AdicionarItemDeServicoUseCase.executar` copia do catálogo e da ficha ·
colunas `descricao`, `preco_unitario`, `percentual_comissao`, `regra_origem`.
**Rotinas.** RT-ATD-002, RT-ATD-006 · **Teste.** `AdicionarItemDeServicoIT.preco_e_snapshot`,
`AdicionarItemDeServicoIT.percentual_e_snapshot`
**Configurável?** Não · **Origem.** 2026-09-05
