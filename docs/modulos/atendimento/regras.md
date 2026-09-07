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

---

### RN-ATD-011 — O desconto é do item, nunca da comanda

**Enunciado.** Desconto é aplicado a uma linha da comanda. Não existe desconto de comanda.

**Motivo.** Um desconto no total teria de ser rateado entre os itens para chegar às comissões — e o
rateio decidiria, sozinho, **de quem** sai o desconto. Numa comanda com corte da Ana e coloração da
Bia, "vinte reais" tiraria dinheiro das duas sem ninguém escolher.

Aplicar no item obriga a escolha a ser feita por quem dá o desconto, que é quem sabe por que está
dando. É atrito de propósito, no único lugar onde ele evita uma conta errada no bolso de outra
pessoa.

**Onde é garantida.** `AplicarDescontoUseCase.executar` recebe o item · a coluna vive em
`item_comanda`, e `comanda.valor_desconto` é a soma.
**Rotinas.** RT-ATD-004 · **Teste.** `AplicarDescontoIT.recepcao_dentro_do_teto`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-ATD-012 — O teto de desconto é do usuário, sempre percentual, e zero sem identidade

**Enunciado.** Quem tem `comanda:discount` pode descontar até o teto do seu perfil
(`LimitesDoUsuarioApi`). A conferência é **percentual sobre o valor cheio do item**, mesmo quando o
desconto foi digitado em dinheiro. Sem usuário identificado, o teto é zero.

**Motivo.** A permissão responde "pode dar desconto?"; o teto responde "de quanto?". São perguntas
diferentes, e separá-las é o que permite dar alçada sem dar o poder inteiro.

Num item de R$ 80,00, "50%" e "R$ 40,00" são o mesmo desconto — um limite que só olhasse o campo
percentual recusaria o primeiro e aceitaria o segundo, e o furo não precisaria de má-fé: bastaria
digitar do jeito que a recepção já digita.

O perfil vem do **banco**, não do token: token é autocontido e vale até expirar, então um
rebaixamento no meio do expediente ainda apareceria como alçada antiga — e a trilha registraria a
permissão que a pessoa já não tinha.

Zero sem identidade porque job agendado e consumidor de evento agem sem ninguém logado. Falha
fechada: quem não tem rosto não dá desconto.

**Onde é garantida.** `AplicarDescontoUseCase.conferirTeto` · `LimitesDoUsuario` lê o perfil do
banco · `MapaDePermissoes.descontoMaximoPercentual`.
**Rotinas.** RT-ATD-004 · **Teste.** `AplicarDescontoIT.recepcao_acima_do_teto`,
`AplicarDescontoIT.teto_nao_e_contornavel_por_valor`,
`AplicarDescontoIT.sem_identidade_nao_desconta`, `ComandasWebIT.desconto_acima_do_teto`
**Configurável?** O teto por perfil, em `MapaDePermissoes` · **Origem.** 2026-09-05

---

### RN-ATD-013 — Quem paga o desconto é configuração; o percentual continua snapshot

**Enunciado.** `descontoAfetaComissao` decide a base da comissão: `false` (padrão) mantém a base no
valor cheio do serviço, `true` usa o total já descontado. O `percentual_comissao` do executante
**não** é relido da ficha ao recalcular.

**Motivo.** Quem paga o desconto — o salão ou o profissional — é a pergunta 2 de
`13-perguntas-em-aberto`, e a resposta muda de salão para salão. O padrão é `false` porque é o que
não surpreende: o profissional executou o serviço inteiro, e a decisão de baixar o preço foi de
outra pessoa.

Recalcular a comissão é refazer a **conta**, não a **regra**. Reler a ficha aqui apagaria o
snapshot de RN-ATD-010 em silêncio, e o efeito só apareceria no repasse, meses depois.

**Onde é garantida.** `ComandasJdbc.recalcularComissoesDoItem` — o percentual sai da própria linha
de `executante`.
**Rotinas.** RT-ATD-004 · **Teste.** `AplicarDescontoIT.desconto_nao_afeta_comissao`,
`AplicarDescontoIT.desconto_afeta_comissao`, `AplicarDescontoIT.percentual_continua_snapshot`
**Configurável?** Sim — `descontoAfetaComissao` · **Origem.** 2026-09-05

---

### RN-ATD-014 — O assistente não precisa executar o serviço; o principal precisa

**Enunciado.** O executante `PRINCIPAL` tem de ter a habilidade do serviço (RN-ATD-007). O
`ASSISTENTE` não — basta estar ativo.

**Motivo.** Quem lava o cabelo não está executando a coloração; está ajudando quem executa. É a
mesma pergunta com respostas diferentes porque os papéis são diferentes, e é disso que o campo
`papel` serve.

Exigir a habilidade obrigaria o salão a cadastrar toda assistente como colorista — e aí a agenda
passaria a oferecê-la para marcar coloração **sozinha**. O cadastro feito para pagar comissão
mudaria quem o sistema acha que pode atender.

**Onde é garantida.** `AdicionarExecutanteUseCase` não chama `ProfissionalApi.executa`, e o
javadoc diz por quê.
**Rotinas.** RT-ATD-005 · **Teste.** `AdicionarExecutanteIT.assistente_dispensa_habilidade`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-ATD-015 — Um principal por item, e ele não sai

**Enunciado.** Todo item de serviço tem exatamente um executante `PRINCIPAL`. Ele não pode ser
removido; trocar quem executou é remover o item e lançá-lo de novo.

**Motivo.** Dois principais pagam duas comissões cheias pelo mesmo trabalho, e as duas linhas
parecem certas olhando a comanda — o erro só aparece quando o repasse do mês não bate.

Nenhum principal é pior: o item continuaria faturando e pararia de pagar alguém, sem nada acusar.

Quem executou é parte do que a linha **afirma**, não um campo dela. Refazer o item é mais trabalho e
é o trabalho certo.

**Onde é garantida.** Índice único parcial `um_principal_por_item` ·
`RemoverExecutanteUseCase` recusa o principal.
**Rotinas.** RT-ATD-005 · **Teste.** `AdicionarExecutanteIT.o_indice_recusa_dois_principais`,
`AdicionarExecutanteIT.principal_nao_sai`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-ATD-016 — As comissões de um item não passam de 100%

**Enunciado.** A soma dos percentuais dos executantes de um item é no máximo 100. Acrescentar
alguém que estouraria o total é recusado (`ER-ATD-COMISSAO_PASSA_DE_CEM`).

**Motivo.** Acima disso o item paga mais em comissão do que rendeu, e o jeito de chegar lá é
digitar **90** onde se queria **9**.

O erro não aparece na comanda — ela fecha normalmente. Vira problema no fechamento do mês, quando o
repasse não bate e ninguém sabe qual das trezentas linhas está errada.

**Onde é garantida.** `AdicionarExecutanteUseCase.cabeNoItem`.
**Rotinas.** RT-ATD-005 · **Teste.** `AdicionarExecutanteIT.nao_passa_de_cem`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-ATD-017 — Produto só entra na comanda se estiver à venda, ativo e com preço

**Enunciado.** Lançar produto exige que ele exista, esteja ativo, tenha finalidade que permita
venda e tenha preço. Cada falha tem código próprio: `PRODUTO_NAO_ENCONTRADO`,
`PRODUTO_NAO_E_VENDAVEL`, `PRODUTO_SEM_PRECO`.

**Motivo.** São três correções diferentes para quem está no balcão com a cliente esperando: procurar
outro produto, desistir porque é tinta, ou chamar quem pode precificar. Um "não deu" só serviria
para as três e faria a recepção adivinhar.

Sem preço é recusa e não zero, como no serviço (RN-ATD-005): zero é um brinde, e a falha de cadastro
viraria faturamento perdido silencioso.

**Onde é garantida.** `AdicionarItemDeProdutoUseCase.vendavel` · `ProdutoResumo.vendavel`.
**Rotinas.** RT-ATD-003 · **Teste.** `AdicionarItemDeProdutoIT.produto_inexistente`,
`AdicionarItemDeProdutoIT.consumo_interno_nao_vende`,
`AdicionarItemDeProdutoIT.produto_desativado`, `AdicionarItemDeProdutoIT.produto_sem_preco`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-ATD-018 — Item de produto não tem executante, e não mexe no estoque

**Enunciado.** Item de tipo `PRODUTO` não recebe linha de `executante`. Lançá-lo **não** baixa
saldo de estoque, e `custo_medio_unitario` fica nulo.

**Motivo.** Produto paga 0% de comissão por padrão (pergunta 3 de `13-perguntas-em-aberto`), e com
zero a linha de executante seria uma comissão de nada para alguém que ninguém escolheu. O preço é
não registrar quem vendeu — real, e listado nas pendências.

A baixa acontece no fechamento (RT-ATD-006), junto com pagamento e comissão, numa transação só:
comanda aberta é intenção, e item lançado por engano seria estoque baixado por engano. O custo vem
de lá também, porque é a baixa que sabe de qual lote a unidade saiu — preenchê-lo agora com o custo
de hoje faria a margem de uma venda antiga mudar quando a próxima caixa chegasse mais cara.

**Onde é garantida.** `AdicionarItemDeProdutoUseCase` não insere executante ·
`AdicionarExecutanteUseCase` recusa item de produto (`ER-ATD-ITEM_NAO_E_SERVICO`).
**Rotinas.** RT-ATD-003 · **Teste.** `AdicionarItemDeProdutoIT.produto_nao_tem_executante`,
`AdicionarExecutanteIT.produto_nao_tem_executante`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-ATD-019 — Comanda com saldo aberto não fecha

**Enunciado.** Fechar exige que a soma dos pagamentos seja **exatamente** o valor líquido. Menos
recusa com `ER-ATD-COMANDA_COM_SALDO_ABERTO`, informando quanto falta; mais recusa com
`ER-ATD-COMANDA_PAGA_A_MAIS`. Comanda **vazia** fecha.

**Motivo.** Fechar devendo faria a comanda afirmar que o atendimento foi pago quando não foi, e o
faturamento do dia passaria a incluir dinheiro que ninguém recebeu.

Quem não vai receber agora tem `FIADO`; quem vai dar de graça tem `CORTESIA`. As duas quitam
(RN-FIN-003), e é por isso que a exigência não atrapalha nenhum caso real.

Comanda vazia é exceção porque RN-ATD-001 dá uma aberta por cliente: uma vazia esquecida bloquearia
a próxima daquela cliente para sempre e ficaria na barra do balcão sem nunca sair.

**O lado "pago a mais" foi acrescentado por RT-ATD-007** e não existia antes: enquanto aberta,
nenhum pagamento passa do que falta. Só reabrir e tirar item produz essa situação — e fechar assim
registraria faturamento maior do que o atendimento, com o troco devido sem rastro nenhum.

**Onde é garantida.** `FecharComandaUseCase` compara o total pago com o líquido.
**Rotinas.** RT-ATD-006, RT-ATD-007 · **Teste.** `FecharComandaIT.saldo_aberto_nao_fecha`,
`FecharComandaIT.fiado_fecha`, `FecharComandaIT.comanda_vazia_fecha`,
`ComandasWebIT.fechamento_pelo_http`, `ReabrirEEstornarIT.pago_a_mais_nao_fecha`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-ATD-020 — O fechamento é atômico, e falha inteiro

**Enunciado.** Conferir pagamento, marcar `FECHADA`, baixar o estoque de cada produto e publicar o
evento acontecem numa transação. Qualquer falha desfaz tudo, inclusive o fechamento. O `update` do
status usa `where status = 'ABERTA'` e vem **antes** da baixa.

**Motivo.** Comanda fechada com estoque não baixado é a pior combinação possível: o dinheiro entrou,
o produto saiu, e o sistema acha que ele ainda está no armário.

A ordem importa por concorrência: com o fechamento primeiro, dois fechamentos simultâneos não
chegam os dois ao FEFO — quem perde a corrida muda zero linhas e aborta antes de tocar o estoque.

**Onde é garantida.** `@Transactional` em `FecharComandaUseCase` · `ComandasJdbc.fechar` com a
condição de status · a baixa depois do fechamento.
**Rotinas.** RT-ATD-006 · **Teste.** `FecharComandaIT.falha_na_baixa_desfaz_o_fechamento`,
`FecharComandaIT.fechar_duas_vezes`, `FecharComandaIT.estoque_insuficiente_recusa`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-ATD-021 — O custo do item vem da baixa; a comissão congela por parar de mudar

**Enunciado.** `item_comanda.custo_medio_unitario` é preenchido no fechamento, com a média da
retirada devolvida pela baixa FEFO. Não existe passo de "congelar comissão": os valores param
porque a comanda fechada não aceita mais alteração.

**Motivo.** Quem sabe de qual lote cada unidade saiu é a baixa (RN-EST-012). Preencher o custo no
lançamento com o custo médio do produto faria a margem daquela venda mudar quando a próxima caixa
chegasse mais cara — o oposto do que o snapshot existe para garantir.

E um passo de congelamento copiaria valores que já estão certos para as colunas que já os contêm:
`percentual_comissao` e `regra_origem` são snapshot desde o lançamento (RN-ATD-010), e
`base_calculo`/`valor_comissao` acompanham o desconto só enquanto a comanda está aberta.

**Onde é garantida.** `FecharComandaUseCase.baixarProdutos` · `ComandasJdbc.definirCustoDoItem` ·
RN-ATD-006 impedindo alteração depois de fechada.
**Rotinas.** RT-ATD-006 · **Teste.** `FecharComandaIT.custo_chega_ao_item`,
`FecharComandaIT.fechada_nao_muda_mais`, `FecharComandaIT.so_servico_nao_mexe_no_estoque`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-ATD-022 — Desfazer devolve as unidades ao lote de onde saíram, por movimento contrário

**Enunciado.** Reabrir e estornar devolvem ao estoque o que a comanda tirou, **nos lotes de
origem**, gravando movimento de tipo `DEVOLUCAO` com sinal contrário. O movimento da baixa não é
apagado. A operação é idempotente.

**Motivo.** As unidades saíram de lotes com custos e validades diferentes; devolvê-las a um lote
genérico faria o custo médio e o FEFO mentirem a partir dali.

E movimento não se reescreve (RN-EST-006) — é aqui que essa promessa é cobrada pela primeira vez: o
razão de estoque ganha a linha que corrige, e as duas continuam legíveis.

A idempotência vem da própria soma: a devolução olha o saldo líquido daquela origem em cada lote e
só devolve o que ainda está negativo. Não há marca de "já devolvido" para manter em dia — o razão
responde. Isso importa porque quem chama são rotinas que mexem em dinheiro fechado, e repetir uma
chamada é o tipo de coisa que acontece.

**Onde é garantida.** `EstoqueApi.devolver` · `LotesJdbc.pendentesDaOrigem` (o `having sum < 0`) ·
`ReabrirComandaUseCase` e `EstornarComandaUseCase`.
**Rotinas.** RT-ATD-007 · **Teste.** `ReabrirEEstornarIT.devolve_ao_lote_certo`,
`ReabrirEEstornarIT.devolucao_e_movimento_novo`, `ReabrirEEstornarIT.devolucao_idempotente`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-ATD-023 — Reabrir e estornar exigem motivo; estornar guarda o dele na comanda

**Enunciado.** Os dois atos exigem motivo não vazio (`ER-ATD-MOTIVO_OBRIGATORIO`). O do estorno é
gravado em `comanda.motivo_estorno`; o da reabertura fica só na auditoria.

**Motivo.** O motivo separa a correção legítima do desfazimento silencioso. Sem ele a trilha diz
quem e quando, e a pergunta do dono — *por que esta comanda de trezentos reais foi estornada?* —
fica sem resposta. É a mesma exigência de RT-AGD-005 para cancelar agendamento.

A assimetria é deliberada: `ESTORNADA` é terminal, e a linha vai ficar ali para sempre afirmando que
um atendimento pago foi desfeito — *por quê* faz parte do que ela afirma, e a tela da comanda
precisa mostrar. Reabrir é transitório: a comanda volta à vida normal, e o que sobra é trilha, que é
da auditoria.

**Onde é garantida.** `MotivoDoAto.exigir` · coluna `comanda.motivo_estorno` (V38) ·
`@NotBlank` na borda.
**Rotinas.** RT-ATD-007 · **Teste.** `ReabrirEEstornarIT.motivo_obrigatorio`,
`ReabrirEEstornarIT.estorno_guarda_motivo`, `ComandasWebIT.reabertura_sem_motivo`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-ATD-024 — Estornar não apaga; e estornada é terminal

**Enunciado.** O estorno muda o status e mantém itens e pagamentos. De `ESTORNADA` não se reabre nem
se estorna de novo. Reabrir e estornar só valem para comanda `FECHADA`.

**Motivo.** Apagar as linhas faria sumir a única explicação de por que o caixa do dia tem um buraco —
e é justamente ela que alguém vai procurar. A comanda estornada continua contando o que aconteceu;
o que ela deixa de ser é faturamento.

Terminal porque desfazer um desfazimento é a porta para o histórico virar rascunho. Se o estorno foi
errado, o registro certo é um atendimento novo — não a ressurreição do antigo.

**Onde é garantida.** `ComandasJdbc.estornar` e `reabrir` com `where status = 'FECHADA'` ·
nenhum caminho sai de `ESTORNADA`.
**Rotinas.** RT-ATD-007 · **Teste.** `ReabrirEEstornarIT.estorno_nao_apaga`,
`ReabrirEEstornarIT.estornada_e_terminal`, `ReabrirEEstornarIT.aberta_nao_reabre`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-ATD-025 — A tela de fechamento não calcula dinheiro

**Enunciado.** Total, desconto e restante são exibidos como vieram do servidor. Toda ação da tela
(lançar, remover, descontar, pagar) devolve a comanda ou a conta inteira, e a tela substitui o que
mostra. O front não soma, não subtrai e não arredonda.

**Motivo.** É RN-ATD-008 vista do outro lado. Duas somas — a da tela e a do banco — divergem no dia
em que alguém arredondar diferente, e a divergência aparece como um centavo que impede o fechamento,
com a cliente esperando.

A única conta que a tela faz é "há quantas horas esta comanda está aberta", para o alerta de R-UX-07:
ela não decide dinheiro, e o dado que a responde já veio.

**Onde é garantida.** `Comandas.tsx` só lê `valorLiquido`, `totalPago` e `restante` ·
as mutações devolvem `ComandaDetalhada`/`ContaDaComanda` e a tela invalida as três consultas juntas.
**Rotinas.** RT-ATD-008 · **Teste.** `Comandas.test.tsx` — "mostra o que falta receber sem calcular
nada", "libera o fechamento quando a conta zera"
**Configurável?** Não · **Origem.** 2026-09-05
