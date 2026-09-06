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
