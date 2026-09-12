# Regras de negócio — módulo `estoque`

---

### RN-EST-001 — Venda, consumo interno ou ambos: uma entidade só

**Enunciado.** Produto tem uma `finalidade`: `VENDA`, `CONSUMO_INTERNO` ou `AMBOS`. Não há
entidades separadas para o que se vende e o que se gasta.

**Motivo.** O mesmo frasco de shampoo lava o cabelo aqui e vai para a sacola quando alguém pede.
Duas tabelas obrigariam a cadastrar duas vezes o que é um produto só — e o saldo se dividiria entre
elas: o frasco vendido no balcão não baixaria do saldo que a coloração usa, e o inventário nunca
fecharia.

**Onde é garantida.** `produto.finalidade` (`check`) · `FinalidadeDoProduto.podeSerVendido`.
**Rotinas.** RT-EST-001 · **Teste.** `CadastroDeProdutoIT.ambos`,
`CadastroDeProdutoIT.consumo_interno`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-EST-002 — Sem preço é cadastro válido; consumo interno nunca tem preço

**Enunciado.** Produto de venda pode existir sem preço — não é vendável até ter um. Produto de
`CONSUMO_INTERNO` **não pode** ter preço de venda, nem no cadastro, nem depois.

**Motivo.** A caixa chega, alguém cadastra, e a margem se decide depois. Recusar o cadastro por
falta de preço faria a recepção inventar um número para conseguir salvar — que é exatamente o
número errado que apareceria na venda. Quem recusa é a venda, como em RN-ATD-005.

E o que não se vende não tem preço: um número ali acabaria lido por alguma tela de venda, e a tinta
apareceria no balcão.

**Onde é garantida.** `constraint consumo_interno_nao_tem_preco` ·
`CadastrarProdutoUseCase.ProdutoValido.precoCabeNaFinalidade` · `ProdutoResumo.vendavel`.
**Rotinas.** RT-EST-001 · **Teste.** `CadastroDeProdutoIT.sem_preco_e_cadastro_valido`,
`CadastroDeProdutoIT.consumo_interno_nao_tem_preco`,
`CadastroDeProdutoIT.precificar_consumo_interno`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-EST-003 — Quem cadastra não precifica, e mudar a finalidade não apaga o preço

**Enunciado.** Cadastrar e corrigir produto exige `estoque:write`; definir ou remover o preço de
venda exige `produto:price:write`. Informar preço no cadastro exige as duas. Tornar
`CONSUMO_INTERNO` um produto que tem preço é **recusado**, não limpo.

**Motivo.** As duas permissões existem separadas desde RT-IAM-007, e a recepção tem a primeira e
não a segunda: quem recebe a mercadoria cadastra, quem decide margem precifica.

Se o preço entrasse no mesmo ato sem checagem própria, a separação seria decorativa — bastaria
preencher o campo opcional. E se mudar a finalidade limpasse o preço junto, a recepção o apagaria
pela porta dos fundos: a permissão deixaria de valer por causa de um efeito colateral.

O passo a mais — alguém que possa mexer em preço o remove antes — é o que mantém a fronteira
honesta.

**Onde é garantida.** `@PreAuthorize` de `CadastrarProdutoUseCase` (com a condição sobre o preço) ·
`DefinirPrecoDeVendaUseCase` · `AtualizarProdutoUseCase` recusa a virada · rota de preço separada.
**Rotinas.** RT-EST-001 · **Teste.** `CadastroDeProdutoIT.recepcao_cadastra_sem_preco`,
`CadastroDeProdutoIT.virar_consumo_interno_com_preco`, `ProdutosWebIT.recepcao_nao_precifica`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-EST-004 — A unidade é lista fechada, e não há conversão automática

**Enunciado.** `unidade` é uma de `UN`, `ML`, `L`, `G`, `KG`. Não há texto livre e não há conversão
entre unidades.

**Motivo.** `quantidade` é numérica em toda parte. `"un"`, `"und"` e `"unid"` digitados à mão viram
três unidades diferentes, e a soma de saldo passa a depender de como cada pessoa escreveu.

Sem unidade, a ficha técnica (RT-EST-007) não tem como dizer "40 g de tinta". E converter grama em
quilo automaticamente é o tipo de esperteza que erra por mil quando alguém cadastra errado — o
produto é cadastrado na unidade em que o salão o usa.

**Onde é garantida.** `produto.unidade` (`check`) · `UnidadeDeMedida` · validação de borda no
controller.
**Rotinas.** RT-EST-001 · **Teste.** `ProdutosWebIT.unidade_invalida`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-EST-005 — Produto não se apaga; desativa-se

**Enunciado.** Não há remoção de produto. `ativo = false` tira da lista de venda e do cadastro
corrente; a listagem completa continua mostrando.

**Motivo.** O produto aparece em lote, movimento e item de comanda de meses atrás. Apagá-lo
deixaria o histórico apontando para o nada — e o item da comanda guarda a descrição em snapshot
justamente porque a linha precisa continuar legível, mas o saldo e o custo não são snapshot.

**Onde é garantida.** Não existe rota de remoção · `DefinirAtivacaoDoProdutoUseCase` ·
`idx_produto_ativo`.
**Rotinas.** RT-EST-001 · **Teste.** `CadastroDeProdutoIT.lista_de_cadastro`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-EST-006 — Movimento é imutável; saldo é projeção

**Enunciado.** Não existe coluna de saldo. O saldo de um lote é `sum(quantidade)` dos movimentos
dele. Movimento nunca é alterado nem apagado: correção é movimento novo, de sinal contrário
(RT-EST-005).

**Motivo.** Mesma disciplina do livro-razão. Sem coluna, não há saldo que desande: nenhuma gravação
concorrente perde uma baixa, e nenhum valor errado precisa de script para consertar — se a soma dos
movimentos está certa, o saldo está certo por construção.

E alterar um movimento mudaria o passado: o custo de uma venda já feita passaria a ser outro, e a
margem do mês fechado mudaria sozinha.

O preço é uma agregação em toda leitura de lote, e é barato — um salão tem dezenas de lotes por
produto.

**Onde é garantida.** Ausência de coluna de saldo em `lote` · `LotesJdbc.CAMPOS` calcula a projeção ·
nenhum `update`/`delete` em `movimento_estoque` no código.
**Rotinas.** RT-EST-002 · **Teste.** `EntradaDeLoteIT.saldo_e_projecao`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-EST-007 — O sinal da quantidade distingue entrada de saída; o tipo é rótulo

**Enunciado.** `movimento_estoque.quantidade` é positiva na entrada e negativa na saída, e nunca
zero. O campo `tipo` classifica, mas não participa da soma.

**Motivo.** Se o tipo decidisse o sinal, toda consulta de saldo precisaria conhecer a lista de
tipos que somam e a dos que subtraem — e a primeira que esquecesse um tipo novo daria um número
plausível e errado, que é a pior espécie.

Zero fica fora porque movimento de zero não move nada e só polui a projeção.

**Onde é garantida.** `check (quantidade <> 0)` · a projeção soma sem olhar o tipo ·
`TipoDeMovimento` documenta que é rótulo.
**Rotinas.** RT-EST-002 · **Teste.** `EntradaDeLoteIT.entrada_cria_lote_e_movimento`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-EST-008 — O custo é do lote, e a ordem de saída é a do FEFO

**Enunciado.** Cada lote guarda o próprio `custo_unitario` e a própria `validade`. A listagem de
lotes de um produto vem ordenada por `validade nulls last, recebido_em` — vence primeiro, sai
primeiro. Lote vencido **entra**; produto desativado **não** recebe entrada.

**Motivo.** A mesma prateleira tem unidades que custaram vinte e unidades que custaram vinte e
cinco: custo no produto perderia a diferença, e com ela a margem de cada venda.

A ordem vem pronta do banco porque é a que RT-EST-004 vai consumir — deixar cada chamador
reinventá-la é como metade das implementações de FEFO erra, e o erro só aparece quando alguma coisa
vence no armário. O que não tem validade sai por último: não corre risco de perder.

Caixa vencida entra porque é o caso que mais precisa de registro — ela chegou assim e vai voltar
para o fornecedor. Já produto desativado recebendo mercadoria é quase sempre o cadastro errado
escolhido na tela, e descobrir isso depois custa um inventário.

**Onde é garantida.** `lote.custo_unitario` · `idx_lote_fefo` · `LotesJdbc.doProduto` ordena ·
`RegistrarEntradaDeLoteUseCase` recusa produto inativo.
**Rotinas.** RT-EST-002 · **Teste.** `EntradaDeLoteIT.dois_lotes`, `EntradaDeLoteIT.ordem_fefo`,
`EntradaDeLoteIT.vencida_entra`, `EntradaDeLoteIT.produto_inativo`,
`ProdutosWebIT.lotes_em_ordem_fefo`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-EST-009 — Saldo, custo médio e valor em estoque são projeção, nunca coluna

**Enunciado.** O saldo de um produto é a soma dos movimentos de todos os seus lotes; o custo médio é
a ponderação dos lotes com saldo positivo; o valor em estoque é o produto dos dois. Nenhum dos três
é gravado.

**Motivo.** É RN-EST-006 um nível acima, e o que se ganha é o mesmo, ampliado: **não existe
"recalcular o estoque"**. Não há job noturno, script de correção, nem a conversa de *"o saldo do
sistema está errado, precisa rodar aquela rotina"* — que é a conversa que todo sistema de estoque
com coluna de saldo acaba tendo.

**Onde é garantida.** `LotesJdbc.SALDO` calcula tudo em uma consulta · nenhuma coluna de saldo em
`produto` ou `lote`.
**Rotinas.** RT-EST-003 · **Teste.** `SaldoEcustoMedioIT.custo_medio_ponderado`,
`EntradaDeLoteIT.saldo_e_projecao`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-EST-010 — Só lote com saldo positivo pondera; sem estoque, o custo médio é nulo

**Enunciado.** A média ponderada considera apenas lotes com saldo maior que zero. A quantidade
total soma todos, inclusive saldos negativos. Sem nenhum lote positivo, o custo médio é **nulo** e
o valor em estoque é zero.

**Motivo.** Lote zerado não tem unidade para ponderar, e incluí-lo puxaria a média para o custo de
mercadoria que já saiu — a média é do que está na prateleira. Lote negativo tem peso negativo:
subtrairia valor de um estoque que existe, e a média poderia ficar negativa.

A quantidade mostra o negativo porque escondê-lo seria esconder o erro que precisa ser corrigido
(RT-EST-005). Estoque que nunca mostra número estranho é estoque em que ninguém confia.

E nulo não é zero: zero significaria "tenho unidades que não custaram nada", que é o caso real da
amostra do fornecedor. É a mesma distinção de `ServicoApi.precoEm` (RN-ATD-005) e de
`taxa_prevista` (RN-FIN-004).

**Onde é garantida.** Os `case when saldo > 0` da consulta de saldo · `SaldoDoProduto.custoMedio`
anulável.
**Rotinas.** RT-EST-003 · **Teste.** `SaldoEcustoMedioIT.lote_zerado_nao_pondera`,
`SaldoEcustoMedioIT.sem_estoque_sem_media`, `SaldoEcustoMedioIT.saldo_negativo_aparece`,
`SaldoEcustoMedioIT.amostra_de_graca`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-EST-011 — A saída é FEFO: vence primeiro, sai primeiro

**Enunciado.** Toda baixa consome os lotes na ordem `validade nulls last, recebido_em`. Uma baixa
pode atravessar vários lotes.

**Motivo.** Num salão o que estraga não é o que chegou primeiro, é o que vence primeiro. FIFO
deixaria vencer no armário a caixa comprada ontem com validade curta — que é justamente o
desperdício que o sistema existe para evitar.

O que não tem validade sai por último: não corre risco de perder.

**Onde é garantida.** `idx_lote_fefo` · a ordem no `travarLotesDoProduto` e no `doProduto` ·
`BaixaFefo.baixar` percorre nessa ordem.
**Rotinas.** RT-EST-004 · **Teste.** `BaixaFefoIT.fefo_e_nao_fifo`,
`BaixaFefoIT.sem_validade_por_ultimo`, `BaixaFefoIT.baixa_em_varios_lotes`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-EST-012 — O custo da retirada é a média do que saiu, não o custo médio do produto

**Enunciado.** Cada movimento de saída grava o custo **do lote** de onde a unidade saiu. A baixa
devolve a média ponderada dessas parcelas, que é o custo daquela retirada.

**Motivo.** São perguntas diferentes: o custo médio do produto (RN-EST-010) vale o inventário — é a
média do que está na prateleira; o custo da retirada mede a margem daquela venda — é a média do que
saiu.

Usar o custo médio do produto na venda faria a margem de uma venda mudar quando a próxima caixa
chegasse mais cara, que é exatamente o que o snapshot existe para impedir (RN-ATD-010).

**Onde é garantida.** `movimento_estoque.custo_unitario` recebe o custo do lote ·
`BaixaFefo` pondera as parcelas.
**Rotinas.** RT-EST-004 · **Teste.** `BaixaFefoIT.baixa_em_varios_lotes`,
`BaixaFefoIT.custo_da_retirada_nao_e_o_do_produto`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-EST-013 — A baixa trava os lotes antes de decidir, em dois comandos

**Enunciado.** A baixa executa `select ... for update` sobre os lotes do produto e **só então** lê
os saldos, em outro comando. Saldo insuficiente recusa a operação inteira, sem baixar nada, e a
mensagem informa quanto há.

**Motivo.** Sem lock, duas comandas fechando ao mesmo tempo leem o mesmo saldo, as duas decidem que
cabe, e o lote fica negativo. O saldo ser projeção **não** protege disso: projeção resolve saldo
errado, não corrida.

E lock com leitura no mesmo comando também não protege — foi assim na primeira versão, com teste
provando as duas baixas passando. Em `READ COMMITTED`, quem acorda do `for update` reavalia a linha
travada na versão nova, mas a subconsulta do saldo, na lista de seleção, já foi resolvida com o
snapshot do início do comando: a segunda transação acorda com o lock na mão e o saldo de antes.

Cada comando pega um snapshot novo, então a leitura separada já enxerga o commit do vizinho.

A ordem do `order by` é a mesma para todas as transações: quem trava as mesmas linhas em ordens
diferentes faz deadlock.

**Onde é garantida.** `LotesJdbc.travarLotesDoProduto` (só trava) · `BaixaFefo.baixar` lê depois ·
a transação da baixa.
**Rotinas.** RT-EST-004 · **Teste.** `BaixaFefoIT.concorrencia_na_ultima_unidade`,
`BaixaFefoIT.saldo_insuficiente`, `BaixaFefoIT.recusa_nao_deixa_rastro`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-EST-014 — Perda, ajuste e devolução ao fornecedor exigem motivo, e quem garante é o banco

**Enunciado.** Movimento de tipo `PERDA`, `AJUSTE` ou `DEVOLUCAO_AO_FORNECEDOR` só é gravado com
`observacao` de pelo menos 3 caracteres. Os demais tipos não exigem: eles têm **origem** — item de
comanda, serviço, nota de entrada — e a origem explica a saída.

**Motivo.** "Faltaram três shampoos" sem motivo não se investiga. E o motivo está na constraint, e
não só no caso de uso, porque os próximos caminhos de escrita — o inventário (RT-EST-006) e a ficha
técnica (RT-EST-007) — vão gravar ajuste por baixo; sem a constraint, o primeiro que esquecesse
gravaria perda sem explicação sem nada reclamar.

Três caracteres, e não "não vazio": um campo obrigatório que aceita `"x"` é um campo que a recepção
preenche com `"x"`. Três não resolve o problema — resolve o contorno mais barato dele.

**Onde é garantida.** V45: constraint `movimento_com_motivo` ·
`MovimentoComMotivo.exigirMotivo` para a recusa ter frase.
**Rotinas.** RT-EST-005 · **Teste.** `PerdaAjusteDevolucaoIT.perda_exige_motivo`,
`PerdaAjusteDevolucaoIT.motivo_de_uma_letra`, `PerdaAjusteDevolucaoIT.banco_exige_motivo`
**Configurável?** Não · **Origem.** 2026-09-12

---

### RN-EST-015 — Movimento de estoque é imutável por permissão

**Enunciado.** `movimento_estoque` recebe apenas `INSERT` e `SELECT` da role da aplicação. Corrigir
um movimento é lançar o movimento contrário.

**Motivo.** O glossário já chamava o movimento de *"evento imutável que altera saldo"* — e a role
tinha `UPDATE` e `DELETE`. O razão (V40), o vale (V41) e o fechamento (V42) todos impõem a
imutabilidade pelo banco, com a mesma justificativa escrita três vezes; o movimento de estoque, que
é **onde a ideia começou**, era o único que dependia de disciplina de quem escreve o código.

Importa mais justamente aqui. Perda e ajuste são exatamente os movimentos que alguém vai querer
"consertar" com um `update`: registrou 5 de perda e eram 3. O movimento contrário deixa rastro dos
dois fatos; o `update` apaga o erro e, com ele, a explicação.

Nada no código fazia `update` nem `delete` nesta tabela — conferido antes de revogar. A devolução
de estoque (RN-ATD-022) já funcionava por `insert` de movimento contrário, que é o padrão que o
revoke transformou em regra.

**Onde é garantida.** V45: `revoke update, delete on movimento_estoque from salao_app`.
**Rotinas.** RT-EST-005 · **Teste.** `PerdaAjusteDevolucaoIT.movimento_e_imutavel`
**Configurável?** Não · **Origem.** 2026-09-12

---

### RN-EST-016 — Devolução ao fornecedor é outro tipo que devolução da cliente

**Enunciado.** `DEVOLUCAO` é a volta das unidades quando uma comanda é reaberta ou estornada —
quantidade **positiva**, nos lotes de onde saíram. `DEVOLUCAO_AO_FORNECEDOR` é mercadoria indo
embora — quantidade **negativa**, sempre num lote informado.

**Motivo.** Com um tipo só, *"quanto devolvemos ao fornecedor este mês?"* dependeria de filtrar por
sinal e de ninguém nunca ter misturado os dois. São fatos diferentes: contrapartes diferentes,
sinais opostos, e um é pergunta de compras enquanto o outro é de atendimento.

É a mesma razão pela qual o razão separa `RECEITA_SERVICO` de `RECEITA_PRODUTO` — mesmo sinal,
mesma direção, significados diferentes. Aqui os sinais são **opostos**, então o argumento é mais
forte.

E o tipo antigo **não** foi renomeado para `DEVOLUCAO_DA_CLIENTE`: a tabela acabou de ficar
append-only (RN-EST-015) e as linhas existentes são todas devolução de cliente. Reescrever
histórico para deixar um nome simétrico é pior do que conviver com a assimetria.

**Onde é garantida.** V45 estende o `check` de tipo · `DevolverAoFornecedorUseCase` nega o sinal ·
`BaixaFefo.devolver` continua escrevendo `DEVOLUCAO`.
**Rotinas.** RT-EST-005 · **Teste.** `PerdaAjusteDevolucaoIT.devolucao_ao_fornecedor`,
`PerdaAjusteDevolucaoIT.devolucao_negativa_recusada`
**Configurável?** Não · **Origem.** 2026-09-12

---

### RN-EST-017 — Perda é por FEFO; ajuste e devolução são por lote

**Enunciado.** `PERDA` recebe produto e quantidade, e sai do lote que vence primeiro. `AJUSTE` e
`DEVOLUCAO_AO_FORNECEDOR` exigem o **lote**. Ajuste aceita os dois sinais; unidades que aparecem
entram no custo do lote em que apareceram.

**Motivo.** A diferença não é burocrática: é **quem sabe o quê**. "Sumiram três shampoos" não diz de
qual lote, e FEFO é a resposta certa quando ninguém sabe. Já quem conta tem a etiqueta na mão, e
quem devolve ao fornecedor está devolvendo uma caixa de uma nota específica.

E o lote obrigatório no ajuste positivo protege o custo médio: atribuir unidades achadas a um lote
escolhido por FEFO **inventaria um custo**, e o custo médio ponderado (RT-EST-003) passaria a
mentir a partir dali. É a mesma razão pela qual a devolução de comanda devolve aos lotes de onde
saiu (RN-ATD-022).

**Onde é garantida.** `RegistrarPerdaUseCase` usa `BaixaFefo` · `MovimentoComMotivo.noLote` lê o
custo do próprio lote · o lock e a releitura em comando separado repetem o cuidado de RT-EST-004.
**Rotinas.** RT-EST-005 · **Teste.** `PerdaAjusteDevolucaoIT.perda_sai_por_fefo`,
`PerdaAjusteDevolucaoIT.ajuste_positivo_usa_o_custo_do_lote`,
`PerdaAjusteDevolucaoIT.ajuste_nao_fica_negativo`
**Configurável?** Não · **Origem.** 2026-09-12

---

### RN-EST-018 — O esperado do inventário congela no instante da contagem, e o ajuste é um delta

**Enunciado.** Cada linha de `inventario_item` guarda `quantidade_esperada` — o saldo do lote **no
momento em que aquele lote foi contado**. Ao aplicar, o movimento de `AJUSTE` é
`contada − esperada`. Linha que bateu não vira movimento.

**Motivo.** As duas alternativas estragam o resultado.

**Congelar ao abrir o inventário** faria uma venda durante a contagem virar divergência falsa: o
sistema esperava 10, venderam 2, você contou 7, e o ajuste sairia −3 quando a divergência real é
−1. Num salão a contagem leva horas e não se para de vender.

**Recalcular ao aplicar** faria o número confirmado não ser o número visto — a confirmação deixaria
de confirmar coisa alguma. É o mesmo defeito que a versão da prévia resolve no fechamento
(RN-FIN-019).

Congelando por linha, o ajuste é um **delta** e não um "passe a valer o contado": movimento
legítimo depois da contagem continua valendo em cima da correção — 10 − 2 − 3 dá 5, que é
exatamente 7 − 2. A janela de erro fica do tamanho de uma prateleira, não do inventário inteiro.

E contar o mesmo lote de novo é **recontar**: substitui a linha, com o esperado do novo instante.
Quem recontou está corrigindo a própria contagem, não achando mais unidades.

**Onde é garantida.** `ContarLoteUseCase` grava `lote.quantidade()` do instante ·
`inventario_item` com `unique (inventario_id, lote_id)` e `on conflict do update` ·
`ItemContado.divergencia()`.
**Rotinas.** RT-EST-006 · **Teste.** `InventarioIT.venda_durante_a_contagem`,
`InventarioIT.recontar_substitui`, `InventarioIT.linha_que_bateu`
**Configurável?** Não · **Origem.** 2026-09-12

---

### RN-EST-019 — Um inventário aberto por salão, e aplicar é tudo ou nada

**Enunciado.** Existe no máximo **um** inventário `ABERTO` por estabelecimento, garantido por
índice único parcial. Aplicar escreve todos os ajustes numa transação: se um lote não aceitar o
seu, o inventário inteiro é recusado, nada é escrito e ele **continua aberto**.

**Motivo.** Dois inventários abertos contariam o mesmo lote com esperados diferentes, e aplicar os
dois somaria duas correções para uma única divergência — o estoque ficaria pior do que antes da
contagem. O índice é parcial para o histórico de encerrados ficar; é a mesma forma do `EXCLUDE`
parcial do fechamento (RN-FIN-022).

E aplicar parcialmente deixaria o estoque corrigido pela metade sem ninguém saber qual metade. A
recusa **nomeia o produto e o lote**: com dez lotes conferidos, "saldo insuficiente" sozinho não
diz o que recontar.

Lote não contado **não aparece** na folha, e não é tratado como zero: um zero implícito faria um
inventário parcial zerar o estoque que ninguém olhou.

**Onde é garantida.** V46: índice `um_inventario_aberto_por_salao` ·
`AbrirInventarioUseCase` traduz a violação em frase · `AplicarInventarioUseCase` numa transação,
com `InventariosJdbc.travar` e `encerrar` condicionado a `status = 'ABERTO'`.
**Rotinas.** RT-EST-006 · **Teste.** `InventarioIT.um_aberto_por_salao`,
`InventarioIT.ajuste_que_nao_cabe`, `InventarioIT.aplicar_duas_vezes`,
`InventarioIT.aplicar_libera`, `InventarioIT.cancelar_libera`
**Configurável?** Não · **Origem.** 2026-09-12

---

### RN-EST-020 — O inventário é documento de trabalho; o movimento que ele produz, não

**Enunciado.** `inventario` e `inventario_item` aceitam `UPDATE` — recontar corrige a linha. Nenhum
dos dois aceita `DELETE`. O movimento de `AJUSTE` que sai da aplicação é imutável (RN-EST-015).
Inventário `APLICADO` não se cancela: desfazer é lançar o ajuste contrário.

**Motivo.** A separação é o ponto. Quem recontou uma prateleira precisa corrigir a contagem; quem
aplicou precisa não poder mexer no movimento. Misturar as duas coisas numa tabela só obrigaria a
escolher entre contagem imutável (que não serve) e movimento mutável (que apaga a explicação).

Sem `DELETE` porque uma contagem apagada é uma prateleira que ninguém sabe se foi contada. Recontar
sobrescreve; desistir cancela o inventário inteiro — e a contagem descartada **fica**, porque é
informação, especialmente se a recontagem der outro número.

O motivo do `AJUSTE` é o próprio inventário ("Inventário de 12/09/2026"), o que satisfaz RN-EST-014
com algo mais útil que texto livre por linha: ele amarra o movimento ao documento que tem a
contagem, o esperado e quem contou.

**Onde é garantida.** V46: `revoke delete` nas duas tabelas ·
`CancelarInventarioUseCase` recusa `APLICADO` · `AplicarInventarioUseCase` monta o motivo.
**Rotinas.** RT-EST-006 · **Teste.** `InventarioIT.aplicado_nao_cancela`,
`InventarioIT.cancelar_libera`, `InventarioIT.ajuste_leva_motivo`
**Configurável?** Não · **Origem.** 2026-09-12

---

### RN-EST-021 — A ficha técnica é previsão, e mora no estoque

**Enunciado.** `ficha_tecnica` liga serviço a produto com a quantidade padrão consumida. Só produto
de finalidade `CONSUMO_INTERNO` ou `AMBOS` entra. Definir é substituir: uma linha por par
serviço × produto. Alterar a ficha **não** recalcula consumo já lançado.

**Motivo.** A tabela precisa morar num dos dois módulos. No catálogo, ela obrigaria aquele módulo a
conhecer unidade de medida, finalidade do produto e a regra de que só se consome o que é
consumível — conceitos de estoque migrando para onde não moram. Aqui, o custo é uma chamada a
`ServicoApi.porId`, exatamente o que `financeiro` já faz para a regra de comissão (RN-FIN-007).

Produto só de venda é recusado com código próprio em vez de aceito em silêncio: um shampoo de
prateleira usado no serviço precisa ter finalidade `AMBOS`, e essa decisão tem consequência de
preço (produto de consumo interno não tem preço de venda), então ela é do cadastro.

E a ficha é **configuração, não histórico** — ela diz o que o serviço consome de agora em diante.
Mesma separação da regra de comissão, que muda sem recalcular o que já foi pago.

**Onde é garantida.** V47: `unique (estabelecimento_id, servico_id, produto_id)` e
`check (quantidade > 0)` · `DefinirItemDaFichaUseCase` confere serviço e finalidade.
**Rotinas.** RT-EST-007 · **Teste.** `FichaTecnicaIT.definir_e_substituir`,
`FichaTecnicaIT.produto_de_venda_recusado`, `FichaTecnicaIT.produto_ambos_entra`,
`FichaTecnicaIT.servico_de_outro_salao`, `FichaTecnicaIT.ficha_nao_recalcula_o_passado`
**Configurável?** A ficha é a configuração · **Origem.** 2026-09-12

---

### RN-EST-022 — Falta de insumo não impede o fechamento da comanda

**Enunciado.** No fechamento, cada item de serviço consome o que a ficha manda, **até o
disponível**. O que faltou é registrado em log e não interrompe nada. Serviço sem ficha não consome.

**Motivo.** O serviço **já foi executado**. Se o sistema diz que não há tinta, ou o saldo está
errado ou a profissional substituiu o produto — e nos dois casos o atendimento aconteceu. Recusar o
fechamento pararia o balcão com a cliente na cadeira, por um motivo contábil.

É diferente da venda de produto, onde alguém **lançou** o item: vender o que não existe é erro de
operação, e ali a recusa está certa. Aqui ninguém lançou nada — a ficha inferiu.

A diferença acumulada entre o previsto e o real é exatamente o que o inventário (RT-EST-006) existe
para pegar, e é por isso que ele veio antes desta rotina.

**Onde é garantida.** `BaixaFefo.baixarAteODisponivel` · `EstoqueApi.consumirPorServico` ·
`ConsumoDeServico.incompletas()` alimenta o log.
**Rotinas.** RT-EST-007 · **Teste.** `FichaTecnicaIT.falta_de_insumo_nao_trava_o_balcao`,
`FichaTecnicaIT.sem_estoque_nenhum`, `FichaTecnicaIT.servico_sem_ficha`
**Configurável?** Não · **Origem.** 2026-09-12

---

### RN-EST-023 — A devolução de estoque não filtra por tipo de item

**Enunciado.** Reabrir e estornar comanda chamam `estoque.devolver("ITEM_COMANDA", item.id())` para
**todo** item, sem olhar se ele é produto ou serviço.

**Motivo.** Havia um `if (tipo == PRODUTO)` nos dois, e ele passou a estar errado no instante em que
o serviço ganhou ficha técnica: a coloração consome tinta com a **mesma origem**, e o filtro deixava
a tinta baixada para trás na reabertura — o estoque ficava permanentemente menor do que a realidade,
e ninguém veria até o inventário.

O filtro também era um vazamento: saber se aquela origem gerou venda ou consumo é assunto do
estoque. `devolver` é idempotente e devolve zero quando não há nada, então **perguntar é mais
barato e mais correto do que decidir do lado de fora**.

**Onde é garantida.** `ReabrirComandaUseCase.devolverOEstoque` ·
`EstornarComandaUseCase` · `BaixaFefo.devolver` (idempotente por construção, RN-ATD-022).
**Rotinas.** RT-EST-007 · **Teste.** `FichaTecnicaIT.reabrir_devolve_o_consumo`
**Configurável?** Não · **Origem.** 2026-09-12

---

### RN-EST-024 — Estoque mínimo nulo não alerta; zero alerta ao zerar

**Enunciado.** `produto.estoque_minimo` é anulável. **Nulo** significa "ninguém definiu mínimo" e o
produto não entra na lista de alertas. **Zero** significa "avise quando acabar". Produto exatamente
no mínimo já alerta.

**Motivo.** Quinta aplicação da mesma distinção neste projeto — `preco_venda` ("zero é um preço"),
`taxa_prevista` ("zero seria taxa isenta"), `custo_medio` ("zero seriam unidades que não custaram
nada"), `comissao_percentual` (RN-FIN-005) — e aqui ela muda o produto de verdade.

Com `not null default 0`, todo produto nasceria com "avise quando acabar" e a tela de alertas
viraria uma lista de cem linhas. **Um alerta que ninguém lê é pior que nenhum**, porque ocupa o
lugar do que importa.

E no mínimo já é hora de comprar: o mínimo é o piso, não o alarme de incêndio.

**Onde é garantida.** V48: coluna anulável, `check (… is null or … >= 0)` e índice parcial
`where estoque_minimo is not null` · `LotesJdbc.abaixoDoMinimo` filtra por
`estoque_minimo is not null`, e não por `coalesce(…, 0)`.
**Rotinas.** RT-EST-008 · **Teste.** `AlertasDeEstoqueIT.sem_minimo_nao_alerta`,
`AlertasDeEstoqueIT.minimo_zero_alerta_ao_zerar`, `AlertasDeEstoqueIT.limite_do_minimo`,
`AlertasDeEstoqueIT.remover_o_minimo`
**Configurável?** O mínimo é a configuração · **Origem.** 2026-09-12

---

### RN-EST-025 — Vencido entra na mesma lista do vencendo; lote esgotado não entra

**Enunciado.** O alerta de validade traz lotes com `validade` dentro da janela (30 dias por padrão)
**e os já vencidos**, com `diasRestantes` negativo. Lote sem validade nunca vence. Lote com saldo
zerado não aparece. Cada linha carrega o **custo em risco**.

**Motivo.** Vencido e vencendo entram juntos porque a ação é a mesma: tirar da prateleira. Duas
listas garantiriam que uma delas nunca fosse aberta.

Lote esgotado que vai vencer não é problema de ninguém — alertar sobre ele treina a pessoa a
ignorar a lista.

E o custo em risco é o número que faz o dono agir: *"R$ 200 vão virar lixo em 10 dias"* move; *"3
lotes vencendo"* não.

Trinta dias porque é o prazo em que dá para usar ou devolver ao fornecedor (RT-EST-005). Sete seria
tarde para devolver; noventa encheria a lista com o que ainda não é problema.

**Onde é garantida.** `LotesJdbc.vencendoEm` com `having sum(quantidade) > 0` e
`validade is not null` · `ConsultarAlertasUseCase.DIAS_DE_AVISO_PADRAO`.
**Rotinas.** RT-EST-008 · **Teste.** `AlertasDeEstoqueIT.lote_vencendo`,
`AlertasDeEstoqueIT.lote_vencido`, `AlertasDeEstoqueIT.fora_da_janela`,
`AlertasDeEstoqueIT.sem_validade`, `AlertasDeEstoqueIT.lote_esgotado_nao_alerta`
**Configurável?** A janela é parâmetro; o padrão, não · **Origem.** 2026-09-12

---

### RN-EST-026 — O código guardado é o que o leitor emite; não há validação de dígito verificador

**Enunciado.** `produto.codigo_barras` é texto livre, opcional, guardado **exatamente** como
chegou. A única restrição de forma é não ser vazio nem conter espaço em branco. O sistema **não**
valida dígito verificador de EAN-13/UPC-A, não normaliza zero à esquerda e não converte entre
padrões.

**Motivo.** O que o sistema precisa é reencontrar o produto quando o mesmo leitor ler a mesma
etiqueta. Isso é comparação literal, e qualquer normalização afasta os dois lados: o código
gravado deixa de ser o código lido.

Validar o dígito recusaria código legítimo no dia seguinte ao da implantação:

- produto importado com etiqueta fora do padrão brasileiro;
- produto **reetiquetado** pelo distribuidor, com código interno de 5 ou 6 dígitos;
- etiqueta **impressa pelo próprio salão** para o que chegou sem código — o caso mais comum em
  produto de revenda fracionado;
- Code 128 e QR, que não têm dígito verificador no mesmo sentido.

Recusar o cadastro nesses casos não protege ninguém: empurra a pessoa a inventar um código falso
que passe na validação, e aí o campo mente.

O espaço em branco é proibido porque é sempre erro: nenhum padrão de código de barras o contém, e
o que aparece é sujeira de recorte e cola. Proibi-lo evita o cadastro que nunca vai casar com bipe
nenhum — e que é indistinguível a olho do certo.

**Onde é garantida.** `V49__codigo_de_barras.sql`, `check codigo_barras_sem_espaco` ·
`DefinirCodigoDeBarrasUseCase` (apara as pontas, recusa espaço no meio) ·
`ProdutosJdbc.porCodigoDeBarras` compara com `=`.
**Rotinas.** RT-EST-009 · **Teste.** `CodigoDeBarrasIT.espaco_no_meio`,
`CodigoDeBarrasIT.digito_invalido_e_aceito`, `CodigoDeBarrasIT.codigo_interno_aceito`,
`CodigoDeBarrasIT.codigo_aparado`, `CodigoDeBarrasIT.bipar_acha`
**Configurável?** Não · **Origem.** 2026-09-12

---

### RN-EST-027 — Um código por produto, único no salão; sem código é o normal

**Enunciado.** No mesmo estabelecimento, um código de barras pertence a **no máximo um** produto.
O campo é nulo por padrão e continua nulo para a maioria dos produtos: insumo de bancada não tem
etiqueta. Gravar código vazio **remove** o código. O mesmo código pode existir em salões
diferentes sem conflito.

**Motivo.** Se dois produtos dividissem o código, o bipe teria de perguntar qual dos dois — e a
pessoa que bipou está com a mão ocupada. A ambiguidade tem de ser impossível no cadastro, não
resolvida no balcão.

O índice é **parcial** (`where codigo_barras is not null`) porque em índice único do PostgreSQL
nulos não colidem entre si, mas explicitar a condição deixa o índice pequeno: ele indexa as poucas
dezenas de produtos etiquetados, não as centenas sem código.

Nulo é o estado normal, e não um cadastro incompleto: nada no sistema exige o código, e a tela não
deve cobrá-lo.

**Onde é garantida.** `V49__codigo_de_barras.sql`, índice `um_codigo_de_barras_por_salao` ·
`DefinirCodigoDeBarrasUseCase` consulta o dono antes para dar a mensagem com o **nome** de quem já
tem o código, e o `DuplicateKeyException` cobre a corrida.
**Rotinas.** RT-EST-009 · **Teste.** `CodigoDeBarrasIT.codigo_duplicado`,
`CodigoDeBarrasIT.mesmo_ean_em_saloes_diferentes`, `CodigoDeBarrasIT.remover_libera`,
`CodigoDeBarrasIT.vazio_remove`
**Configurável?** Não · **Origem.** 2026-09-12

---

### RN-EST-028 — O bipe escolhe; lançar é do humano. Código desconhecido desarma o botão

**Enunciado.** Na tela de comanda, ler um código **seleciona** o produto — não lança o item. Um
código que não pertence a nenhum produto vendável **limpa a seleção** e mostra o código lido.

**Motivo.** O lançamento dá baixa FEFO no estoque (RT-EST-004) e entra na conta da cliente;
desfazer um bipe torto custa um estorno. E a quantidade quase nunca é 1 em venda de balcão, então
lançar no gatilho obrigaria a corrigir logo depois.

Limpar a seleção é o ponto que importa: sem isso, bipar o produto A, depois bipar o B que não tem
código cadastrado, deixava o aviso na tela **e o A ainda armado no botão** — e o próximo clique
lançava o item errado, exatamente no fluxo em que ninguém está olhando a tela.

O código lido aparece no aviso porque "não achei" tem duas saídas diferentes: leitura errada
(bipar de novo) ou produto sem código cadastrado (escolher na lista e cadastrar depois). Sem o
número, quem bipou não sabe em qual dos dois está.

**Onde é garantida.** `Lancar.tsx`, `aoLer` · `produtoDoCodigo` em `api/comandas.ts`.
**Rotinas.** RT-EST-009 · **Teste.**
`Comandas.test.tsx` — *bipar o código escolhe o produto, e lançar continua sendo do humano*,
*código que não é de nenhum produto do balcão não seleciona nada e diz qual foi*
**Configurável?** Não · **Origem.** 2026-09-12
